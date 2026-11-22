import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.*;
import java.util.Random;

public class PocketFC19 extends MIDlet implements CommandListener {
    private Display display;
    private FCCanvas canvas;
    private Command exitCmd;

    public PocketFC19() {
        display = Display.getDisplay(this);
        canvas = new FCCanvas();
        exitCmd = new Command("Exit", Command.EXIT, 1);
        canvas.addCommand(exitCmd);
        canvas.setCommandListener(this);
    }

    public void startApp() {
        display.setCurrent(canvas);
        canvas.start();
    }

    public void pauseApp() {
        canvas.stop();
    }

    public void destroyApp(boolean unconditional) {
        canvas.stop();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exitCmd) {
            destroyApp(false);
            notifyDestroyed();
        }
    }

    static class FCCanvas extends GameCanvas implements Runnable {
        private static final int STATE_MENU = 0;
        private static final int STATE_TEAM_SELECT = 1;
        private static final int STATE_MATCH = 2;
        private static final int STATE_HALFTIME = 3;
        private static final int STATE_FULLTIME = 4;
        private static final int STATE_PENALTY = 5;
        private static final int STATE_CUP_BRACKET = 6;

        private volatile boolean running = false;
        private Thread loopThread;
        private Random rng = new Random();

        private int w, h;
        private int state = STATE_MENU;
        private int menuIndex = 0;
        private final String[] menuItems = {"KICK OFF", "KNOCKOUT CUP", "PENALTIES", "CONTROLS"};
        private boolean showControls = false;

        // Clubs with Primary Jersey, Secondary Trim, and Shorts colors
        private final String[] teamNames = {
            "MADRID ROYAL", "BARCA CITY", "MANCHESTER RED", "TURIN BLACK",
            "LONDON BLUE", "MUNICH RED", "PARIS SAINT", "KARACHI UNITED"
        };
        private final String[] teamShort = {"MAD", "BAR", "MUN", "TUR", "LON", "FCB", "PSG", "KHI"};
        private final int[] kitPrimary = {
            0xFFFFFF, 0xA50044, 0xDA291C, 0x111111,
            0x034694, 0xDC052D, 0x004170, 0x1E824C
        };
        private final int[] kitSecondary = {
            0xF1C40F, 0x004D98, 0xFBE122, 0xFFFFFF,
            0xFFFFFF, 0xFFFFFF, 0xDA291C, 0xFFFFFF
        };
        private final int[] kitShorts = {
            0xFFFFFF, 0x004D98, 0xFFFFFF, 0x111111,
            0x034694, 0xDC052D, 0x004170, 0xFFFFFF
        };
        private final int[] teamRating = {91, 90, 88, 89, 86, 89, 88, 84};

        private int myTeam = 0;
        private int cpuTeam = 1;
        private int selectStep = 0;
        private boolean isCupMode = false;
        private int cupRound = 1;

        // Scrolling 7v7 Stadium Pitch
        private static final int PITCH_W = 440;
        private static final int PITCH_H = 700;
        private int camX = 0, camY = 0;

        // 14 Players: 0..6 User, 7..13 CPU
        private int[] px = new int[14];
        private int[] py = new int[14];
        private int[] homeX = new int[14];
        private int[] homeY = new int[14];
        private int[] pDir = new int[14];  // 0=Up, 1=Down, 2=Left, 3=Right
        private int[] pAnim = new int[14]; // Running leg frame
        private int activePlayer = 6;
        private int ballOwner = 6;

        // Ball
        private int ballX, ballY, ballVX, ballVY, ballZ = 0, ballVZ = 0;
        private int ballSpin = 0;
        private int shootCharge = 0;
        private boolean chargingShot = false;

        // Match State
        private int myScore = 0, cpuScore = 0;
        private int matchHalf = 1;
        private int matchSeconds = 0;
        private int frameTick = 0;
        private String banner = "";
        private int bannerTimer = 0;
        private int cpuActionCooldown = 0;

        // Penalty Shootout
        private int penRound = 1;
        private int penMyGoals = 0, penCpuGoals = 0;
        private boolean penUserTurn = true;
        private int penAim = 0;
        private int penKeeperDive = 0;
        private int penBallX, penBallY;
        private boolean penBallMoving = false;
        private int penResultTimer = 0;
        private String penMsg = "";

        public FCCanvas() {
            super(true);
            setFullScreenMode(true);
        }

        public void start() {
            w = getWidth();
            h = getHeight();
            running = true;
            loopThread = new Thread(this);
            loopThread.start();
        }

        public void stop() {
            running = false;
        }

        public void run() {
            Graphics g = getGraphics();
            while (running) {
                long t0 = System.currentTimeMillis();
                update();
                render(g);
                flushGraphics();
                long dt = System.currentTimeMillis() - t0;
                if (dt < 45) {
                    try { Thread.sleep(45 - dt); } catch (Exception e) {}
                }
            }
        }

        private int lastKeys = 0;
        private int getJustPressed() {
            int keys = getKeyStates();
            int just = keys & (~lastKeys);
            lastKeys = keys;
            return just;
        }

        private void update() {
            int keys = getKeyStates();
            int just = getJustPressed();

            if (state == STATE_MENU) {
                if (showControls) {
                    if ((just & (FIRE_PRESSED | GAME_A_PRESSED | GAME_B_PRESSED)) != 0) {
                        showControls = false;
                    }
                    return;
                }
                if ((just & UP_PRESSED) != 0) menuIndex = (menuIndex + menuItems.length - 1) % menuItems.length;
                if ((just & DOWN_PRESSED) != 0) menuIndex = (menuIndex + 1) % menuItems.length;
                if ((just & FIRE_PRESSED) != 0) {
                    if (menuIndex == 0) {
                        isCupMode = false;
                        selectStep = 0;
                        state = STATE_TEAM_SELECT;
                    } else if (menuIndex == 1) {
                        isCupMode = true;
                        cupRound = 1;
                        selectStep = 0;
                        state = STATE_TEAM_SELECT;
                    } else if (menuIndex == 2) {
                        isCupMode = false;
                        selectStep = 0;
                        state = STATE_TEAM_SELECT;
                    } else if (menuIndex == 3) {
                        showControls = true;
                    }
                }
            } else if (state == STATE_TEAM_SELECT) {
                if ((just & LEFT_PRESSED) != 0) {
                    if (selectStep == 0) myTeam = (myTeam + teamNames.length - 1) % teamNames.length;
                    else cpuTeam = (cpuTeam + teamNames.length - 1) % teamNames.length;
                }
                if ((just & RIGHT_PRESSED) != 0) {
                    if (selectStep == 0) myTeam = (myTeam + 1) % teamNames.length;
                    else cpuTeam = (cpuTeam + 1) % teamNames.length;
                }
                if ((just & FIRE_PRESSED) != 0) {
                    if (isCupMode) {
                        cpuTeam = (myTeam + 2) % teamNames.length;
                        state = STATE_CUP_BRACKET;
                    } else if (selectStep == 0) {
                        selectStep = 1;
                        if (cpuTeam == myTeam) cpuTeam = (myTeam + 1) % teamNames.length;
                    } else {
                        if (menuIndex == 2) startPenaltyShootout();
                        else startMatch();
                    }
                }
            } else if (state == STATE_CUP_BRACKET) {
                if ((just & FIRE_PRESSED) != 0) startMatch();
            } else if (state == STATE_MATCH) {
                updateMatch(keys, just);
            } else if (state == STATE_HALFTIME) {
                if ((just & FIRE_PRESSED) != 0) {
                    matchHalf = 2;
                    matchSeconds = 45;
                    resetKickoff(false);
                    state = STATE_MATCH;
                }
            } else if (state == STATE_FULLTIME) {
                if ((just & FIRE_PRESSED) != 0) {
                    if (isCupMode) {
                        if (myScore == cpuScore) {
                            startPenaltyShootout();
                        } else if (myScore > cpuScore) {
                            if (cupRound < 3) {
                                cupRound++;
                                cpuTeam = (cpuTeam + 3) % teamNames.length;
                                if (cpuTeam == myTeam) cpuTeam = (cpuTeam + 1) % teamNames.length;
                                state = STATE_CUP_BRACKET;
                            } else {
                                state = STATE_MENU;
                            }
                        } else {
                            state = STATE_MENU;
                        }
                    } else {
                        state = STATE_MENU;
                    }
                }
            } else if (state == STATE_PENALTY) {
                updatePenalty(just);
            }
        }

        private void startMatch() {
            myScore = 0;
            cpuScore = 0;
            matchHalf = 1;
            matchSeconds = 0;
            frameTick = 0;
            resetKickoff(true);
            state = STATE_MATCH;
        }

        private void resetKickoff(boolean userKickoff) {
            int cx = PITCH_W / 2;
            int cy = PITCH_H / 2;

            // User 7v7 Formation
            homeX[0] = cx;       homeY[0] = PITCH_H - 42;
            homeX[1] = cx - 90;  homeY[1] = PITCH_H - 140;
            homeX[2] = cx + 90;  homeY[2] = PITCH_H - 140;
homeX[3] = cx - 105; homeY[3] = cy + 95;
homeX[4] = cx + 105; homeY[4] = cy + 95;
homeX[5] = cx - 45;  homeY[5] = cy + 25;
homeX[6] = cx;       homeY[6] = cy;
// CPU 7v7 Formation
homeX[7] = cx;       homeY[7] = 42;
homeX[8] = cx - 90;  homeY[8] = 140;
homeX[9] = cx + 90;  homeY[9] = 140;
homeX[10] = cx - 105; homeY[10] = cy - 95;
homeX[11] = cx + 105; homeY[11] = cy - 95;
homeX[12] = cx - 45;  homeY[12] = cy - 25;
homeX[13] = cx;       homeY[13] = cy - 12;
for (int i = 0; i < 14; i++) {
px[i] = homeX[i];
py[i] = homeY[i];
pDir[i] = (i < 7) ? 0 : 1;
pAnim[i] = 0;
}
if (userKickoff) {
activePlayer = 6;
ballOwner = 6;
} else {
activePlayer = 6;
ballOwner = 13;
px[13] = cx;
py[13] = cy;
py[6] = cy + 28;
}
ballX = px[ballOwner];
ballY = py[ballOwner];
ballZ = 0;
ballVX = 0;
ballVY = 0;
chargingShot = false;
shootCharge = 0;
banner = userKickoff ? "KICK OFF!" : "CPU KICK OFF";
bannerTimer = 25;
}
private void updateMatch(int keys, int just) {
if (bannerTimer > 0) bannerTimer--;
frameTick++;
if (frameTick >= 11) {
frameTick = 0;
matchSeconds++;
if (matchHalf == 1 && matchSeconds >= 45) {
state = STATE_HALFTIME;
return;
} else if (matchHalf == 2 && matchSeconds >= 90) {
state = STATE_FULLTIME;
return;
}
}
int spd = 4;
int moveX = 0, moveY = 0;
if ((keys & LEFT_PRESSED) != 0) { moveX = -spd; pDir[activePlayer] = 2; }
if ((keys & RIGHT_PRESSED) != 0) { moveX = spd; pDir[activePlayer] = 3; }
if ((keys & UP_PRESSED) != 0) { moveY = -spd; pDir[activePlayer] = 0; }
if ((keys & DOWN_PRESSED) != 0) { moveY = spd; pDir[activePlayer] = 1; }
if (moveX != 0 || moveY != 0) {
px[activePlayer] = clamp(px[activePlayer] + moveX, 26, PITCH_W - 26);
py[activePlayer] = clamp(py[activePlayer] + moveY, 26, PITCH_H - 26);
pAnim[activePlayer]++;
}
// Pass / Switch / Tackle
if ((just & (GAME_A_PRESSED | GAME_C_PRESSED)) != 0) {
if (ballOwner >= 0 && ballOwner < 7) {
passBall(activePlayer, true);
} else {
switchToClosestPlayer();
if (ballOwner >= 7 && dist(px[activePlayer], py[activePlayer], ballX, ballY) < 24) {
ballOwner = activePlayer;
banner = "CLEAN TACKLE!";
bannerTimer = 15;
}
}
}
// Shoot with Power Bar
if (ballOwner == activePlayer) {
if ((keys & FIRE_PRESSED) != 0) {
chargingShot = true;
if (shootCharge < 14) shootCharge++;
} else if (chargingShot) {
chargingShot = false;
int power = 8 + shootCharge;
int targetX = (PITCH_W / 2) + ((moveX != 0) ? moveX * 9 : (rng.nextInt(32) - 16));
ballVZ = (shootCharge > 6) ? 5 : 2;
kickBallToward(targetX, 18, power);
shootCharge = 0;
}
} else {
chargingShot = false;
shootCharge = 0;
}
updateAI();
if (ballOwner >= 0) {
ballX = px[ballOwner];
ballY = py[ballOwner] + (ballOwner < 7 ? -7 : 7);
ballZ = 0;
ballSpin++;
} else {
ballX += ballVX;
ballY += ballVY;
ballSpin += 2;
if (ballZ > 0 || ballVZ != 0) {
ballZ += ballVZ;
ballVZ -= 1;
if (ballZ <= 0) {
ballZ = 0;
ballVZ = 0;
}
}
ballVX = (ballVX * 93) / 100;
ballVY = (ballVY * 93) / 100;
int goalLeft = (PITCH_W / 2) - 46;
int goalRight = (PITCH_W / 2) + 46;
if (ballY <= 24 && ballX >= goalLeft && ballX <= goalRight && ballZ < 14) {
myScore++;
banner = "GOOOAL! " + teamShort[myTeam];
bannerTimer = 35;
resetKickoff(false);
return;
}
if (ballY >= PITCH_H - 24 && ballX >= goalLeft && ballX <= goalRight && ballZ < 14) {
cpuScore++;
banner = "CPU GOAL!";
bannerTimer = 35;
resetKickoff(true);
return;
}
if (ballX < 24) { ballX = 24; ballVX = -ballVX; }
if (ballX > PITCH_W - 24) { ballX = PITCH_W - 24; ballVX = -ballVX; }
if (ballY < 24) { ballY = 28; ballVY = 3; }
if (ballY > PITCH_H - 24) { ballY = PITCH_H - 28; ballVY = -3; }
if (ballZ < 8) {
for (int i = 0; i < 14; i++) {
if (dist(px[i], py[i], ballX, ballY) < 13) {
ballOwner = i;
ballZ = 0;
if (i < 7) activePlayer = i;
else cpuActionCooldown = 12;
break;
}
}
}
}
camX = clamp(ballX - (w / 2), 0, PITCH_W - w);
camY = clamp(ballY - (h / 2), 0, PITCH_H - h);
}
private void updateAI() {
if (cpuActionCooldown > 0) cpuActionCooldown--;
px[0] = clamp(ballX, (PITCH_W / 2) - 38, (PITCH_W / 2) + 38);
px[7] = clamp(ballX, (PITCH_W / 2) - 38, (PITCH_W / 2) + 38);
int cpuSkill = 2 + (teamRating[cpuTeam] - 80) / 5;
for (int i = 1; i < 14; i++) {
if (i == 7 || i == activePlayer) continue;
int tx = homeX[i];
int ty = homeY[i];
if (i < 7) {
int shiftY = (ballY - (PITCH_H / 2)) / 2;
ty = clamp(homeY[i] + shiftY, 50, PITCH_H - 50);
if (ballOwner < 0 && dist(px[i], py[i], ballX, ballY) < 75) {
tx = ballX;
ty = ballY;
}
} else {
if (ballOwner == i) {
tx = (PITCH_W / 2) + ((i % 2 == 0) ? -32 : 32);
ty = PITCH_H - 65;
if (cpuActionCooldown == 0) {
if (py[i] > PITCH_H - 190) {
ballVZ = 3;
kickBallToward((PITCH_W / 2) + (rng.nextInt(44) - 22), PITCH_H - 20, 10 + cpuSkill);
} else if (rng.nextInt(18) == 0) {
passBall(i, false);
cpuActionCooldown = 14;
}
}
} else {
int shiftY = (ballY - (PITCH_H / 2)) / 2;
ty = clamp(homeY[i] + shiftY, 50, PITCH_H - 50);
if (dist(px[i], py[i], ballX, ballY) < 115) {
tx = ballX;
ty = ballY;
}
if (ballOwner >= 0 && ballOwner < 7 && dist(px[i], py[i], ballX, ballY) < 12 && cpuActionCooldown == 0) {
ballOwner = i;
cpuActionCooldown = 12;
banner = "INTERCEPTED";
bannerTimer = 14;
}
}
}
int s = (i < 7) ? 3 : cpuSkill;
boolean moved = false;
if (px[i] < tx - 2) { px[i] += s; pDir[i] = 3; moved = true; }
else if (px[i] > tx + 2) { px[i] -= s; pDir[i] = 2; moved = true; }
if (py[i] < ty - 2) { py[i] += s; pDir[i] = 1; moved = true; }
else if (py[i] > ty + 2) { py[i] -= s; pDir[i] = 0; moved = true; }
if (moved) pAnim[i]++;
}
}
private void passBall(int passer, boolean isUser) {
int start = isUser ? 1 : 8;
int end = isUser ? 7 : 14;
int bestMate = -1;
int bestScore = 99999;
for (int i = start; i < end; i++) {
if (i == passer) continue;
int d = dist(px[passer], py[passer], px[i], py[i]);
if (d > 25 && d < bestScore) {
bestScore = d;
bestMate = i;
}
}
ballVZ = 1;
if (bestMate != -1) {
kickBallToward(px[bestMate], py[bestMate], 11);
if (isUser) activePlayer = bestMate;
} else {
kickBallToward(PITCH_W / 2, isUser ? 24 : PITCH_H - 24, 11);
}
}
private void switchToClosestPlayer() {
int best = activePlayer;
int bestD = 99999;
for (int i = 1; i < 7; i++) {
int d = dist(px[i], py[i], ballX, ballY);
if (d < bestD) {
bestD = d;
best = i;
}
}
activePlayer = best;
}
private void kickBallToward(int tx, int ty, int speed) {
int dx = tx - ballX;
int dy = ty - ballY;
int len = isqrt(dx * dx + dy * dy);
if (len == 0) len = 1;
ballVX = (dx * speed) / len;
ballVY = (dy * speed) / len;
ballOwner = -1;
ballX += ballVX * 2;
ballY += ballVY * 2;
}
// --- PENALTY SHOOTOUT ---
private void startPenaltyShootout() {
penRound = 1;
penMyGoals = 0;
penCpuGoals = 0;
penUserTurn = true;
resetPenaltyKick();
state = STATE_PENALTY;
}
private void resetPenaltyKick() {
penAim = 0;
penKeeperDive = 0;
penBallX = w / 2;
penBallY = h - 65;
penBallMoving = false;
penResultTimer = 0;
penMsg = penUserTurn ? "YOUR KICK: AIM & PRESS OK" : "CPU KICK: DIVE & PRESS OK";
}
private void updatePenalty(int just) {
if (penResultTimer > 0) {
penResultTimer--;
if (penResultTimer == 0) {
if (penUserTurn) {
penUserTurn = false;
resetPenaltyKick();
} else {
if (penRound >= 5 && penMyGoals != penCpuGoals) {
myScore = penMyGoals;
cpuScore = penCpuGoals;
state = STATE_FULLTIME;
return;
}
penRound++;
penUserTurn = true;
resetPenaltyKick();
}
}
return;
}
if (!penBallMoving) {
if ((just & LEFT_PRESSED) != 0) penAim = -1;
if ((just & UP_PRESSED) != 0 || (just & DOWN_PRESSED) != 0) penAim = 0;
if ((just & RIGHT_PRESSED) != 0) penAim = 1;
if ((just & FIRE_PRESSED) != 0) {
penBallMoving = true;
if (penUserTurn) {
penKeeperDive = rng.nextInt(3) - 1;
} else {
penKeeperDive = penAim;
penAim = rng.nextInt(3) - 1;
}
}
} else {
int targetX = (w / 2) + (penAim * 54);
penBallY -= 11;
if (penBallX < targetX - 3) penBallX += 5;
else if (penBallX > targetX + 3) penBallX -= 5;
if (penBallY <= 76) {
penBallMoving = false;
if (penAim == penKeeperDive) {
penMsg = "WHAT A SAVE!";
} else {
penMsg = "GOOOAL!";
if (penUserTurn) penMyGoals++;
else penCpuGoals++;
}
penResultTimer = 28;
}
}
}
// --- ENHANCED VISUAL RENDERING ---
private void render(Graphics g) {
if (state == STATE_MENU) renderMenu(g);
else if (state == STATE_TEAM_SELECT) renderTeamSelect(g);
else if (state == STATE_CUP_BRACKET) renderCupBracket(g);
else if (state == STATE_MATCH) renderMatch(g);
else if (state == STATE_HALFTIME) renderOverlay(g, "HALF TIME", teamShort[myTeam] + " " + myScore + " - " + cpuScore + " " + teamShort[cpuTeam], "PRESS OK FOR 2ND HALF");
else if (state == STATE_FULLTIME) renderOverlay(g, "FULL TIME", teamShort[myTeam] + " " + myScore + " - " + cpuScore + " " + teamShort[cpuTeam], "PRESS OK TO CONTINUE");
else if (state == STATE_PENALTY) renderPenalty(g);
}
private void renderMenu(Graphics g) {
// Stadium Spotlight Background
g.setColor(0x071A12);
g.fillRect(0, 0, w, h);
g.setColor(0x114B32);
g.fillArc(-40, -60, w + 80, 220, 0, 360);
// Decorative Pitch Lines on Menu
g.setColor(0x1E824C);
g.drawArc((w / 2) - 55, h - 90, 110, 110, 0, 180);
// Title Crest
g.setColor(0x0B132B);
g.fillRoundRect(16, 12, w - 32, 54, 12, 12);
g.setColor(0xF1C40F);
g.drawRoundRect(16, 12, w - 32, 54, 12, 12);
g.drawString("POCKET FC 19", w / 2, 20, Graphics.TOP | Graphics.HCENTER);
g.setColor(0x5BC0BE);
g.drawString("CHAMPIONS MOBILE", w / 2, 40, Graphics.TOP | Graphics.HCENTER);
if (showControls) {
g.setColor(0x0B132B);
g.fillRoundRect(14, 78, w - 28, h - 105, 10, 10);
g.setColor(0xF1C40F);
g.drawString("CONTROLS", w / 2, 86, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xFFFFFF);
g.drawString("D-Pad: Dribble / Aim", 24, 114, Graphics.TOP | Graphics.LEFT);
g.drawString("OK / 5: Hold to Shoot", 24, 138, Graphics.TOP | Graphics.LEFT);
g.drawString("Soft1 / 1: Pass / Tackle", 24, 162, Graphics.TOP | Graphics.LEFT);
g.setColor(0xF1C40F);
g.drawString("PRESS OK TO RETURN", w / 2, h - 42, Graphics.TOP | Graphics.HCENTER);
return;
}
for (int i = 0; i < menuItems.length; i++) {
int y = 86 + i * 40;
if (i == menuIndex) {
g.setColor(0xF1C40F);
g.fillRoundRect(24, y - 4, w - 48, 30, 10, 10);
g.setColor(0x0B132B);
} else {
g.setColor(0x162447);
g.fillRoundRect(24, y - 4, w - 48, 30, 10, 10);
g.setColor(0xFFFFFF);
}
g.drawString(menuItems[i], w / 2, y + 4, Graphics.TOP | Graphics.HCENTER);
}
}
private void renderTeamSelect(Graphics g) {
g.setColor(0x0B132B);
g.fillRect(0, 0, w, h);
int idx = (selectStep == 0) ? myTeam : cpuTeam;
g.setColor(0xF1C40F);
g.drawString(selectStep == 0 ? "SELECT YOUR CLUB" : "SELECT OPPONENT", w / 2, 16, Graphics.TOP | Graphics.HCENTER);
// Detailed Jersey Graphic (Torso + Sleeves + Stripe + Shorts)
int jx = w / 2;
int jy = 58;
g.setColor(kitPrimary[idx]);
g.fillRect(jx - 22, jy, 44, 46);       // Torso
g.fillRect(jx - 34, jy + 2, 12, 22);   // Left sleeve
g.fillRect(jx + 22, jy + 2, 12, 22);   // Right sleeve
g.setColor(kitSecondary[idx]);
g.fillRect(jx - 22, jy + 14, 44, 10);  // Chest band
g.drawRect(jx - 22, jy, 44, 46);
// Shorts
g.setColor(kitShorts[idx]);
g.fillRect(jx - 20, jy + 48, 18, 18);
g.fillRect(jx + 2, jy + 48, 18, 18);
g.setColor(0xFFFFFF);
g.drawString("<  " + teamNames[idx] + "  >", w / 2, 140, Graphics.TOP | Graphics.HCENTER);
g.setColor(0x5BC0BE);
g.drawString("CLUB OVR: " + teamRating[idx], w / 2, 166, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xF1C40F);
g.drawString("LEFT/RIGHT: BROWSE", w / 2, h - 52, Graphics.TOP | Graphics.HCENTER);
g.drawString("PRESS OK TO CONFIRM", w / 2, h - 30, Graphics.TOP | Graphics.HCENTER);
}
private void renderCupBracket(Graphics g) {
g.setColor(0x0B132B);
g.fillRect(0, 0, w, h);
g.setColor(0xF1C40F);
g.drawString("CHAMPIONS KNOCKOUT CUP", w / 2, 22, Graphics.TOP | Graphics.HCENTER);
String roundName = (cupRound == 1) ? "QUARTER-FINAL" : (cupRound == 2) ? "SEMI-FINAL" : "CHAMPIONS FINAL";
g.setColor(0x5BC0BE);
g.drawString(roundName, w / 2, 54, Graphics.TOP | Graphics.HCENTER);
g.setColor(0x162447);
g.fillRoundRect(20, 95, w - 40, 95, 12, 12);
g.setColor(0xFFFFFF);
g.drawString(teamNames[myTeam], w / 2, 108, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xF1C40F);
g.drawString("VS", w / 2, 134, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xFFFFFF);
g.drawString(teamNames[cpuTeam], w / 2, 160, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xF1C40F);
g.drawString("PRESS OK TO KICK OFF", w / 2, h - 38, Graphics.TOP | Graphics.HCENTER);
}
private void renderMatch(Graphics g) {
// Stadium Surround Track
g.setColor(0x1B263B);
g.fillRect(0, 0, w, h);
// Mowed Grass Stripes
g.setColor(0x1E824C);
g.fillRect(16 - camX, 16 - camY, PITCH_W - 32, PITCH_H - 32);
g.setColor(0x259B5C);
for (int sy = 16; sy < PITCH_H - 32; sy += 56) {
int stripeH = Math.min(28, (PITCH_H - 16) - sy);
g.fillRect(16 - camX, sy - camY, PITCH_W - 32, stripeH);
}
// Perimeter Advertising Boards
g.setColor(0x0B132B);
g.fillRect(16 - camX, 8 - camY, PITCH_W - 32, 8);
g.fillRect(16 - camX, PITCH_H - 16 - camY, PITCH_W - 32, 8);
g.setColor(0xF1C40F);
for (int ax = 24; ax < PITCH_W - 40; ax += 60) {
g.fillRect(ax - camX, 10 - camY, 28, 4);
g.fillRect(ax - camX, PITCH_H - 14 - camY, 28, 4);
}
// Crisp White Pitch Lines
g.setColor(0xFFFFFF);
g.drawRect(24 - camX, 24 - camY, PITCH_W - 48, PITCH_H - 48);
g.drawLine(24 - camX, (PITCH_H / 2) - camY, PITCH_W - 24 - camX, (PITCH_H / 2) - camY);
g.drawArc((PITCH_W / 2) - 48 - camX, (PITCH_H / 2) - 48 - camY, 96, 96, 0, 360);
g.fillArc((PITCH_W / 2) - 3 - camX, (PITCH_H / 2) - 3 - camY, 6, 6, 0, 360);
// Penalty Boxes, 6-Yard Boxes, Penalty Spots
g.drawRect((PITCH_W / 2) - 92 - camX, 24 - camY, 184, 72);
g.drawRect((PITCH_W / 2) - 46 - camX, 24 - camY, 92, 26);
g.fillArc((PITCH_W / 2) - 2 - camX, 74 - camY, 4, 4, 0, 360);
g.drawRect((PITCH_W / 2) - 92 - camX, PITCH_H - 96 - camY, 184, 72);
g.drawRect((PITCH_W / 2) - 46 - camX, PITCH_H - 50 - camY, 92, 26);
g.fillArc((PITCH_W / 2) - 2 - camX, PITCH_H - 76 - camY, 4, 4, 0, 360);
// 3D Goal Frames & Netting (Top & Bottom)
drawGoalNet(g, (PITCH_W / 2) - 46 - camX, 10 - camY, 92, 14);
drawGoalNet(g, (PITCH_W / 2) - 46 - camX, PITCH_H - 24 - camY, 92, 14);
// Draw Animated Sprite Players
for (int i = 0; i < 14; i++) {
int sx = px[i] - camX;
int sy = py[i] - camY;
if (sx < -20 || sx > w + 20 || sy < -20 || sy > h + 20) continue;
// Active Player Selection Triangle & Ring
if (i == activePlayer) {
g.setColor(0xF1C40F);
g.drawArc(sx - 10, sy + 2, 20, 8, 0, 360);
g.fillTriangle(sx - 4, sy - 24, sx + 4, sy - 24, sx, sy - 19);
}
drawSpritePlayer(g, sx, sy, i, pAnim[i]);
}
// Ball Shadow + Checkered Rotating Football
int bx = ballX - camX;
int by = ballY - camY;
g.setColor(0x114B2C);
g.fillArc(bx - 4, by + 2, 8, 5, 0, 360);
int visualBy = by - ballZ;
g.setColor(0xFFFFFF);
g.fillArc(bx - 4, visualBy - 4, 9, 9, 0, 360);
g.setColor(0x111111);
g.drawArc(bx - 4, visualBy - 4, 9, 9, 0, 360);
int dotOffset = (ballSpin % 3) - 1;
g.fillRect(bx + dotOffset, visualBy - 1, 2, 2);
// Shot Power Gauge
if (chargingShot) {
g.setColor(0x0B132B);
g.fillRoundRect((w / 2) - 34, h - 24, 68, 12, 4, 4);
g.setColor(shootCharge > 10 ? 0xE74C3C : (shootCharge > 5 ? 0xF1C40F : 0x2ECC71));
g.fillRect((w / 2) - 31, h - 21, shootCharge * 4 + 4, 6);
}
// Live Radar Minimap (Bottom-Right)
int mmW = 36, mmH = 54;
int mmX = w - mmW - 6;
int mmY = h - mmH - 6;
g.setColor(0x0B132B);
g.fillRect(mmX - 1, mmY - 1, mmW + 2, mmH + 2);
g.setColor(0x16663B);
g.fillRect(mmX, mmY, mmW, mmH);
g.setColor(0xFFFFFF);
g.drawRect(mmX, mmY, mmW, mmH);
g.drawLine(mmX, mmY + mmH / 2, mmX + mmW, mmY + mmH / 2);
for (int i = 0; i < 14; i++) {
g.setColor(i < 7 ? 0x5BC0BE : 0xE74C3C);
int rx = mmX + (px[i] * mmW) / PITCH_W;
int ry = mmY + (py[i] * mmH) / PITCH_H;
g.fillRect(rx - 1, ry - 1, 2, 2);
}
g.setColor(0xF1C40F);
g.fillRect(mmX + (ballX * mmW) / PITCH_W - 1, mmY + (ballY * mmH) / PITCH_H - 1, 2, 2);
// TV Broadcast Scorebug (Top-Left)
g.setColor(0x0B132B);
g.fillRect(4, 4, 152, 18);
g.setColor(kitPrimary[myTeam]);
g.fillRect(6, 6, 4, 14);
g.setColor(kitPrimary[cpuTeam]);
g.fillRect(96, 6, 4, 14);
g.setColor(0xFFFFFF);
g.drawString(teamShort[myTeam] + " " + myScore + "-" + cpuScore + " " + teamShort[cpuTeam], 14, 6, Graphics.TOP | Graphics.LEFT);
g.setColor(0xF1C40F);
g.drawString(matchSeconds + "'", 128, 6, Graphics.TOP | Graphics.LEFT);
// Event Banner
if (bannerTimer > 0) {
g.setColor(0x0B132B);
g.fillRoundRect(24, (h / 2) - 16, w - 48, 26, 8, 8);
g.setColor(0xF1C40F);
g.drawRoundRect(24, (h / 2) - 16, w - 48, 26, 8, 8);
g.drawString(banner, w / 2, (h / 2) - 10, Graphics.TOP | Graphics.HCENTER);
}
}
private void drawGoalNet(Graphics g, int gx, int gy, int gw, int gh) {
g.setColor(0xCCCCCC);
for (int nx = gx + 4; nx < gx + gw; nx += 6) {
g.drawLine(nx, gy, nx, gy + gh);
}
for (int ny = gy + 3; ny < gy + gh; ny += 4) {
g.drawLine(gx, ny, gx + gw, ny);
}
g.setColor(0xFFFFFF);
g.drawRect(gx, gy, gw, gh);
}
private void drawSpritePlayer(Graphics g, int sx, int sy, int idx, int animFrame) {
boolean isUser = (idx < 7);
boolean isKeeper = (idx == 0 || idx == 7);
int jersey = isKeeper ? 0xF39C12 : (isUser ? kitPrimary[myTeam] : kitPrimary[cpuTeam]);
int shorts = isKeeper ? 0x111111 : (isUser ? kitShorts[myTeam] : kitShorts[cpuTeam]);
// Ground Shadow
g.setColor(0x114B2C);
g.fillArc(sx - 6, sy + 4, 12, 5, 0, 360);
// Animated Legs & Boots
int stride = ((animFrame / 3) % 2 == 0) ? -2 : 2;
g.setColor(0xE0AC69); // Skin tone legs
g.fillRect(sx - 4, sy + 1 + stride, 3, 5);
g.fillRect(sx + 1, sy + 1 - stride, 3, 5);
g.setColor(0x111111); // Boots
g.fillRect(sx - 4, sy + 5 + stride, 3, 2);
g.fillRect(sx + 1, sy + 5 - stride, 3, 2);
// Shorts
g.setColor(shorts);
g.fillRect(sx - 5, sy - 2, 10, 4);
// Jersey Torso
g.setColor(jersey);
g.fillRect(sx - 6, sy - 9, 12, 8);
g.setColor(0x111111);
g.drawRect(sx - 6, sy - 9, 12, 8);
// Head & Hair
g.setColor(0xE0AC69);
g.fillArc(sx - 4, sy - 16, 8, 8, 0, 360);
g.setColor(0x2C1E16);
g.fillArc(sx - 4, sy - 16, 8, 4, 0, 180);
}
private void renderPenalty(Graphics g) {
// Stadium Grass & Crowd Stands
g.setColor(0x0B132B);
g.fillRect(0, 0, w, 55);
g.setColor(0x1E824C);
g.fillRect(0, 55, w, h - 55);
// Penalty Box Lines
g.setColor(0xFFFFFF);
g.drawRect((w / 2) - 95, 55, 190, 115);
g.fillArc((w / 2) - 3, h - 62, 6, 6, 0, 360);
// Goal Frame with Netting
int gx = (w / 2) - 72;
drawGoalNet(g, gx, 42, 144, 58);
// Sprite Goalkeeper Diving
int kx = (w / 2) + (penKeeperDive * 46);
drawSpritePlayer(g, kx, 90, penUserTurn ? 7 : 0, penBallMoving ? 3 : 0);
// Aim Reticle
if (!penBallMoving && penResultTimer == 0) {
int ax = (w / 2) + (penAim * 54);
g.setColor(0xF1C40F);
g.drawArc(ax - 9, 64, 18, 18, 0, 360);
g.drawLine(ax - 12, 73, ax + 12, 73);
g.drawLine(ax, 61, ax, 85);
}
// Football
g.setColor(0xFFFFFF);
g.fillArc(penBallX - 5, penBallY - 5, 10, 10, 0, 360);
g.setColor(0x111111);
g.drawArc(penBallX - 5, penBallY - 5, 10, 10, 0, 360);
// Scoreboard HUD
g.setColor(0x0B132B);
g.fillRect(0, 0, w, 22);
g.setColor(0xFFFFFF);
g.drawString("PEN R" + penRound + " | YOU " + penMyGoals + " - " + penCpuGoals + " CPU", w / 2, 4, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xF1C40F);
g.drawString(penMsg, w / 2, h - 28, Graphics.TOP | Graphics.HCENTER);
}
private void renderOverlay(Graphics g, String title, String scoreLine, String prompt) {
g.setColor(0x0B132B);
g.fillRect(0, 0, w, h);
g.setColor(0x162447);
g.fillRoundRect(18, 55, w - 36, 110, 14, 14);
g.setColor(0xF1C40F);
g.drawString(title, w / 2, 72, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xFFFFFF);
g.drawString(scoreLine, w / 2, 116, Graphics.TOP | Graphics.HCENTER);
g.setColor(0x5BC0BE);
g.drawString(prompt, w / 2, h - 48, Graphics.TOP | Graphics.HCENTER);
}
private int clamp(int v, int min, int max) {
return v < min ? min : (v > max ? max : v);
}
private int dist(int x1, int y1, int x2, int y2) {
int dx = x1 - x2, dy = y1 - y2;
return isqrt(dx * dx + dy * dy);
}
private int isqrt(int n) {
if (n <= 0) return 0;
int x = n, y = (x + 1) / 2;
while (y < x) {
x = y;
y = (x + n / x) / 2;
}
return x;
}
}
}
