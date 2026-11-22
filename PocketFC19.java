import javax.microedition.midlet.*;
import javax.microedition.lcdui.*;
import javax.microedition.lcdui.game.*;
import java.util.Random;
import java.util.Vector;

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

    static class Player {
        int x, y, homeX, homeY, dir, anim;
        Player(int hx, int hy, int d) {
            x = hx; y = hy; homeX = hx; homeY = hy; dir = d; anim = 0;
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
        private Vector menuItems = new Vector();
        private boolean showControls = false;

        private Vector teamNames = new Vector();
        private Vector teamShort = new Vector();
        private Vector kitPrimary = new Vector();
        private Vector kitSecondary = new Vector();
        private Vector kitShorts = new Vector();
        private Vector teamRating = new Vector();

        private int myTeam = 0;
        private int cpuTeam = 1;
        private int selectStep = 0;
        private boolean isCupMode = false;
        private int cupRound = 1;

        private static final int PITCH_W = 440;
        private static final int PITCH_H = 700;
        private int camX = 0, camY = 0;

        private Vector players = new Vector();
        private int activePlayer = 6;
        private int ballOwner = 6;

        private int ballX, ballY, ballVX, ballVY, ballZ = 0, ballVZ = 0;
        private int ballSpin = 0;
        private int shootCharge = 0;
        private boolean chargingShot = false;

        private int myScore = 0, cpuScore = 0;
        private int matchHalf = 1;
        private int matchSeconds = 0;
        private int frameTick = 0;
        private String banner = "";
        private int bannerTimer = 0;
        private int cpuActionCooldown = 0;

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
            initData();
        }

        private void initData() {
            menuItems.addElement("KICK OFF");
            menuItems.addElement("KNOCKOUT CUP");
            menuItems.addElement("PENALTIES");
            menuItems.addElement("CONTROLS");

            addClub("MADRID ROYAL", "MAD", 0xFFFFFF, 0xF1C40F, 0xFFFFFF, 91);
            addClub("BARCA CITY", "BAR", 0xA50044, 0x004D98, 0x004D98, 90);
            addClub("MANCHESTER RED", "MUN", 0xDA291C, 0xFBE122, 0xFFFFFF, 88);
            addClub("TURIN BLACK", "TUR", 0x111111, 0xFFFFFF, 0x111111, 89);
            addClub("LONDON BLUE", "LON", 0x034694, 0xFFFFFF, 0x034694, 86);
            addClub("MUNICH RED", "FCB", 0xDC052D, 0xFFFFFF, 0xDC052D, 89);
            addClub("PARIS SAINT", "PSG", 0x004170, 0xDA291C, 0x004170, 88);
            addClub("KARACHI UNITED", "KHI", 0x1E824C, 0xFFFFFF, 0xFFFFFF, 84);
        }

        private void addClub(String name, String sh, int p, int s, int shCol, int ovr) {
            teamNames.addElement(name);
            teamShort.addElement(sh);
            kitPrimary.addElement(new Integer(p));
            kitSecondary.addElement(new Integer(s));
            kitShorts.addElement(new Integer(shCol));
            teamRating.addElement(new Integer(ovr));
        }

        private int getInt(Vector v, int idx) {
            return ((Integer) v.elementAt(idx)).intValue();
        }

        private String getStr(Vector v, int idx) {
            return (String) v.elementAt(idx);
        }

        private Player getP(int idx) {
            return (Player) players.elementAt(idx);
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
            int numTeams = teamNames.size();

            if (state == STATE_MENU) {
                if (showControls) {
                    if ((just & (FIRE_PRESSED | GAME_A_PRESSED | GAME_B_PRESSED)) != 0) {
                        showControls = false;
                    }
                    return;
                }
                if ((just & UP_PRESSED) != 0) menuIndex = (menuIndex + menuItems.size() - 1) % menuItems.size();
                if ((just & DOWN_PRESSED) != 0) menuIndex = (menuIndex + 1) % menuItems.size();
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
                    if (selectStep == 0) myTeam = (myTeam + numTeams - 1) % numTeams;
                    else cpuTeam = (cpuTeam + numTeams - 1) % numTeams;
                }
                if ((just & RIGHT_PRESSED) != 0) {
                    if (selectStep == 0) myTeam = (myTeam + 1) % numTeams;
                    else cpuTeam = (cpuTeam + 1) % numTeams;
                }
                if ((just & FIRE_PRESSED) != 0) {
                    if (isCupMode) {
                        cpuTeam = (myTeam + 2) % numTeams;
                        state = STATE_CUP_BRACKET;
                    } else if (selectStep == 0) {
                        selectStep = 1;
                        if (cpuTeam == myTeam) cpuTeam = (myTeam + 1) % numTeams;
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
                                cpuTeam = (cpuTeam + 3) % numTeams;
                                if (cpuTeam == myTeam) cpuTeam = (cpuTeam + 1) % numTeams;
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
players.removeAllElements();
// User 7v7 (0..6)
players.addElement(new Player(cx, PITCH_H - 42, 0));
players.addElement(new Player(cx - 90, PITCH_H - 140, 0));
players.addElement(new Player(cx + 90, PITCH_H - 140, 0));
players.addElement(new Player(cx - 105, cy + 95, 0));
players.addElement(new Player(cx + 105, cy + 95, 0));
players.addElement(new Player(cx - 45, cy + 25, 0));
players.addElement(new Player(cx, cy, 0));
// CPU 7v7 (7..13)
players.addElement(new Player(cx, 42, 1));
players.addElement(new Player(cx - 90, 140, 1));
players.addElement(new Player(cx + 90, 140, 1));
players.addElement(new Player(cx - 105, cy - 95, 1));
players.addElement(new Player(cx + 105, cy - 95, 1));
players.addElement(new Player(cx - 45, cy - 25, 1));
players.addElement(new Player(cx, cy - 12, 1));
if (userKickoff) {
activePlayer = 6;
ballOwner = 6;
} else {
activePlayer = 6;
ballOwner = 13;
getP(13).x = cx;
getP(13).y = cy;
getP(6).y = cy + 28;
}
ballX = getP(ballOwner).x;
ballY = getP(ballOwner).y;
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
Player ap = getP(activePlayer);
int spd = 4;
int moveX = 0, moveY = 0;
if ((keys & LEFT_PRESSED) != 0) { moveX = -spd; ap.dir = 2; }
if ((keys & RIGHT_PRESSED) != 0) { moveX = spd; ap.dir = 3; }
if ((keys & UP_PRESSED) != 0) { moveY = -spd; ap.dir = 0; }
if ((keys & DOWN_PRESSED) != 0) { moveY = spd; ap.dir = 1; }
if (moveX != 0 || moveY != 0) {
ap.x = clamp(ap.x + moveX, 26, PITCH_W - 26);
ap.y = clamp(ap.y + moveY, 26, PITCH_H - 26);
ap.anim++;
}
if ((just & (GAME_A_PRESSED | GAME_C_PRESSED)) != 0) {
if (ballOwner >= 0 && ballOwner < 7) {
passBall(activePlayer, true);
} else {
switchToClosestPlayer();
ap = getP(activePlayer);
if (ballOwner >= 7 && dist(ap.x, ap.y, ballX, ballY) < 24) {
ballOwner = activePlayer;
banner = "CLEAN TACKLE!";
bannerTimer = 15;
}
}
}
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
Player owner = getP(ballOwner);
ballX = owner.x;
ballY = owner.y + (ballOwner < 7 ? -7 : 7);
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
banner = "GOOOAL! " + getStr(teamShort, myTeam);
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
Player p = getP(i);
if (dist(p.x, p.y, ballX, ballY) < 13) {
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
getP(0).x = clamp(ballX, (PITCH_W / 2) - 38, (PITCH_W / 2) + 38);
getP(7).x = clamp(ballX, (PITCH_W / 2) - 38, (PITCH_W / 2) + 38);
int cpuSkill = 2 + (getInt(teamRating, cpuTeam) - 80) / 5;
for (int i = 1; i < 14; i++) {
if (i == 7 || i == activePlayer) continue;
Player p = getP(i);
int tx = p.homeX;
int ty = p.homeY;
if (i < 7) {
int shiftY = (ballY - (PITCH_H / 2)) / 2;
ty = clamp(p.homeY + shiftY, 50, PITCH_H - 50);
if (ballOwner < 0 && dist(p.x, p.y, ballX, ballY) < 75) {
tx = ballX;
ty = ballY;
}
} else {
if (ballOwner == i) {
tx = (PITCH_W / 2) + ((i % 2 == 0) ? -32 : 32);
ty = PITCH_H - 65;
if (cpuActionCooldown == 0) {
if (p.y > PITCH_H - 190) {
ballVZ = 3;
kickBallToward((PITCH_W / 2) + (rng.nextInt(44) - 22), PITCH_H - 20, 10 + cpuSkill);
} else if (rng.nextInt(18) == 0) {
passBall(i, false);
cpuActionCooldown = 14;
}
}
} else {
int shiftY = (ballY - (PITCH_H / 2)) / 2;
ty = clamp(p.homeY + shiftY, 50, PITCH_H - 50);
if (dist(p.x, p.y, ballX, ballY) < 115) {
tx = ballX;
ty = ballY;
}
if (ballOwner >= 0 && ballOwner < 7 && dist(p.x, p.y, ballX, ballY) < 12 && cpuActionCooldown == 0) {
ballOwner = i;
cpuActionCooldown = 12;
banner = "INTERCEPTED";
bannerTimer = 14;
}
}
}
int s = (i < 7) ? 3 : cpuSkill;
boolean moved = false;
if (p.x < tx - 2) { p.x += s; p.dir = 3; moved = true; }
else if (p.x > tx + 2) { p.x -= s; p.dir = 2; moved = true; }
if (p.y < ty - 2) { p.y += s; p.dir = 1; moved = true; }
else if (p.y > ty + 2) { p.y -= s; p.dir = 0; moved = true; }
if (moved) p.anim++;
}
}
private void passBall(int passer, boolean isUser) {
int start = isUser ? 1 : 8;
int end = isUser ? 7 : 14;
int bestMate = -1;
int bestScore = 99999;
Player pp = getP(passer);
for (int i = start; i < end; i++) {
if (i == passer) continue;
Player pm = getP(i);
int d = dist(pp.x, pp.y, pm.x, pm.y);
if (d > 25 && d < bestScore) {
bestScore = d;
bestMate = i;
}
}
ballVZ = 1;
if (bestMate != -1) {
Player bm = getP(bestMate);
kickBallToward(bm.x, bm.y, 11);
if (isUser) activePlayer = bestMate;
} else {
kickBallToward(PITCH_W / 2, isUser ? 24 : PITCH_H - 24, 11);
}
}
private void switchToClosestPlayer() {
int best = activePlayer;
int bestD = 99999;
for (int i = 1; i < 7; i++) {
Player p = getP(i);
int d = dist(p.x, p.y, ballX, ballY);
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
private void render(Graphics g) {
if (state == STATE_MENU) renderMenu(g);
else if (state == STATE_TEAM_SELECT) renderTeamSelect(g);
else if (state == STATE_CUP_BRACKET) renderCupBracket(g);
else if (state == STATE_MATCH) renderMatch(g);
else if (state == STATE_HALFTIME) renderOverlay(g, "HALF TIME", getStr(teamShort, myTeam) + " " + myScore + " - " + cpuScore + " " + getStr(teamShort, cpuTeam), "PRESS OK FOR 2ND HALF");
else if (state == STATE_FULLTIME) renderOverlay(g, "FULL TIME", getStr(teamShort, myTeam) + " " + myScore + " - " + cpuScore + " " + getStr(teamShort, cpuTeam), "PRESS OK TO CONTINUE");
else if (state == STATE_PENALTY) renderPenalty(g);
}
private void renderMenu(Graphics g) {
g.setColor(0x071A12);
g.fillRect(0, 0, w, h);
g.setColor(0x114B32);
g.fillArc(-40, -60, w + 80, 220, 0, 360);
g.setColor(0x1E824C);
g.drawArc((w / 2) - 55, h - 90, 110, 110, 0, 180);
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
for (int i = 0; i < menuItems.size(); i++) {
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
g.drawString(getStr(menuItems, i), w / 2, y + 4, Graphics.TOP | Graphics.HCENTER);
}
}
private void renderTeamSelect(Graphics g) {
g.setColor(0x0B132B);
g.fillRect(0, 0, w, h);
int idx = (selectStep == 0) ? myTeam : cpuTeam;
g.setColor(0xF1C40F);
g.drawString(selectStep == 0 ? "SELECT YOUR CLUB" : "SELECT OPPONENT", w / 2, 16, Graphics.TOP | Graphics.HCENTER);
int jx = w / 2;
int jy = 58;
g.setColor(getInt(kitPrimary, idx));
g.fillRect(jx - 22, jy, 44, 46);
g.fillRect(jx - 34, jy + 2, 12, 22);
g.fillRect(jx + 22, jy + 2, 12, 22);
g.setColor(getInt(kitSecondary, idx));
g.fillRect(jx - 22, jy + 14, 44, 10);
g.drawRect(jx - 22, jy, 44, 46);
g.setColor(getInt(kitShorts, idx));
g.fillRect(jx - 20, jy + 48, 18, 18);
g.fillRect(jx + 2, jy + 48, 18, 18);
g.setColor(0xFFFFFF);
g.drawString("<  " + getStr(teamNames, idx) + "  >", w / 2, 140, Graphics.TOP | Graphics.HCENTER);
g.setColor(0x5BC0BE);
g.drawString("CLUB OVR: " + getInt(teamRating, idx), w / 2, 166, Graphics.TOP | Graphics.HCENTER);
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
g.drawString(getStr(teamNames, myTeam), w / 2, 108, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xF1C40F);
g.drawString("VS", w / 2, 134, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xFFFFFF);
g.drawString(getStr(teamNames, cpuTeam), w / 2, 160, Graphics.TOP | Graphics.HCENTER);
g.setColor(0xF1C40F);
g.drawString("PRESS OK TO KICK OFF", w / 2, h - 38, Graphics.TOP | Graphics.HCENTER);
}
private void renderMatch(Graphics g) {
g.setColor(0x1B263B);
g.fillRect(0, 0, w, h);
g.setColor(0x1E824C);
g.fillRect(16 - camX, 16 - camY, PITCH_W - 32, PITCH_H - 32);
g.setColor(0x259B5C);
for (int sy = 16; sy < PITCH_H - 32; sy += 56) {
int stripeH = Math.min(28, (PITCH_H - 16) - sy);
g.fillRect(16 - camX, sy - camY, PITCH_W - 32, stripeH);
}
g.setColor(0x0B132B);
g.fillRect(16 - camX, 8 - camY, PITCH_W - 32, 8);
g.fillRect(16 - camX, PITCH_H - 16 - camY, PITCH_W - 32, 8);
g.setColor(0xF1C40F);
for (int ax = 24; ax < PITCH_W - 40; ax += 60) {
g.fillRect(ax - camX, 10 - camY, 28, 4);
g.fillRect(ax - camX, PITCH_H - 14 - camY, 28, 4);
}
g.setColor(0xFFFFFF);
g.drawRect(24 - camX, 24 - camY, PITCH_W - 48, PITCH_H - 48);
g.drawLine(24 - camX, (PITCH_H / 2) - camY, PITCH_W - 24 - camX, (PITCH_H / 2) - camY);
g.drawArc((PITCH_W / 2) - 48 - camX, (PITCH_H / 2) - 48 - camY, 96, 96, 0, 360);
g.fillArc((PITCH_W / 2) - 3 - camX, (PITCH_H / 2) - 3 - camY, 6, 6, 0, 360);
g.drawRect((PITCH_W / 2) - 92 - camX, 24 - camY, 184, 72);
g.drawRect((PITCH_W / 2) - 46 - camX, 24 - camY, 92, 26);
g.fillArc((PITCH_W / 2) - 2 - camX, 74 - camY, 4, 4, 0, 360);
g.drawRect((PITCH_W / 2) - 92 - camX, PITCH_H - 96 - camY, 184, 72);
g.drawRect((PITCH_W / 2) - 46 - camX, PITCH_H - 50 - camY, 92, 26);
g.fillArc((PITCH_W / 2) - 2 - camX, PITCH_H - 76 - camY, 4, 4, 0, 360);
drawGoalNet(g, (PITCH_W / 2) - 46 - camX, 10 - camY, 92, 14);
drawGoalNet(g, (PITCH_W / 2) - 46 - camX, PITCH_H - 24 - camY, 92, 14);
for (int i = 0; i < 14; i++) {
Player p = getP(i);
int sx = p.x - camX;
int sy = p.y - camY;
if (sx < -20 || sx > w + 20 || sy < -20 || sy > h + 20) continue;
if (i == activePlayer) {
g.setColor(0xF1C40F);
g.drawArc(sx - 10, sy + 2, 20, 8, 0, 360);
g.fillTriangle(sx - 4, sy - 24, sx + 4, sy - 24, sx, sy - 19);
}
drawSpritePlayer(g, sx, sy, i, p.anim);
}
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
if (chargingShot) {
g.setColor(0x0B132B);
g.fillRoundRect((w / 2) - 34, h - 24, 68, 12, 4, 4);
g.setColor(shootCharge > 10 ? 0xE74C3C : (shootCharge > 5 ? 0xF1C40F : 0x2ECC71));
g.fillRect((w / 2) - 31, h - 21, shootCharge * 4 + 4, 6);
}
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
Player p = getP(i);
g.setColor(i < 7 ? 0x5BC0BE : 0xE74C3C);
int rx = mmX + (p.x * mmW) / PITCH_W;
int ry = mmY + (p.y * mmH) / PITCH_H;
g.fillRect(rx - 1, ry - 1, 2, 2);
}
g.setColor(0xF1C40F);
g.fillRect(mmX + (ballX * mmW) / PITCH_W - 1, mmY + (ballY * mmH) / PITCH_H - 1, 2, 2);
g.setColor(0x0B132B);
g.fillRect(4, 4, 152, 18);
g.setColor(getInt(kitPrimary, myTeam));
g.fillRect(6, 6, 4, 14);
g.setColor(getInt(kitPrimary, cpuTeam));
g.fillRect(96, 6, 4, 14);
g.setColor(0xFFFFFF);
g.drawString(getStr(teamShort, myTeam) + " " + myScore + "-" + cpuScore + " " + getStr(teamShort, cpuTeam), 14, 6, Graphics.TOP | Graphics.LEFT);
g.setColor(0xF1C40F);
g.drawString(matchSeconds + "'", 128, 6, Graphics.TOP | Graphics.LEFT);
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
int jersey = isKeeper ? 0xF39C12 : (isUser ? getInt(kitPrimary, myTeam) : getInt(kitPrimary, cpuTeam));
int shorts = isKeeper ? 0x111111 : (isUser ? getInt(kitShorts, myTeam) : getInt(kitShorts, cpuTeam));
g.setColor(0x114B2C);
g.fillArc(sx - 6, sy + 4, 12, 5, 0, 360);
int stride = ((animFrame / 3) % 2 == 0) ? -2 : 2;
g.setColor(0xE0AC69);
g.fillRect(sx - 4, sy + 1 + stride, 3, 5);
g.fillRect(sx + 1, sy + 1 - stride, 3, 5);
g.setColor(0x111111);
g.fillRect(sx - 4, sy + 5 + stride, 3, 2);
g.fillRect(sx + 1, sy + 5 - stride, 3, 2);
g.setColor(shorts);
g.fillRect(sx - 5, sy - 2, 10, 4);
g.setColor(jersey);
g.fillRect(sx - 6, sy - 9, 12, 8);
g.setColor(0x111111);
g.drawRect(sx - 6, sy - 9, 12, 8);
g.setColor(0xE0AC69);
g.fillArc(sx - 4, sy - 16, 8, 8, 0, 360);
g.setColor(0x2C1E16);
g.fillArc(sx - 4, sy - 16, 8, 4, 0, 180);
}
private void renderPenalty(Graphics g) {
g.setColor(0x0B132B);
g.fillRect(0, 0, w, 55);
g.setColor(0x1E824C);
g.fillRect(0, 55, w, h - 55);
g.setColor(0xFFFFFF);
g.drawRect((w / 2) - 95, 55, 190, 115);
g.fillArc((w / 2) - 3, h - 62, 6, 6, 0, 360);
int gx = (w / 2) - 72;
drawGoalNet(g, gx, 42, 144, 58);
int kx = (w / 2) + (penKeeperDive * 46);
drawSpritePlayer(g, kx, 90, penUserTurn ? 7 : 0, penBallMoving ? 3 : 0);
if (!penBallMoving && penResultTimer == 0) {
int ax = (w / 2) + (penAim * 54);
g.setColor(0xF1C40F);
g.drawArc(ax - 9, 64, 18, 18, 0, 360);
g.drawLine(ax - 12, 73, ax + 12, 73);
g.drawLine(ax, 61, ax, 85);
}
g.setColor(0xFFFFFF);
g.fillArc(penBallX - 5, penBallY - 5, 10, 10, 0, 360);
g.setColor(0x111111);
g.drawArc(penBallX - 5, penBallY - 5, 10, 10, 0, 360);
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
