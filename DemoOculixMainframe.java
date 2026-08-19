import org.sikuli.script.*;
import java.io.IOException;

/**
 * DemoOculixMainframe — OculiX POC: pilote l'émulateur IBM i (5250) tn5250j
 * jusqu'à l'écran d'accueil de PUB400.com, tape user/pass et détecte
 * le IBM i Main Menu qui prouve le login réussi.
 *
 * Ne repose sur AUCUN DOM, AUCUNE API, AUCUN hook. 100% pixel matching
 * (OpenCV Finder) + OCR embarqué (Tesseract via Legerix). Java pur ~170
 * lignes, s'intègre à n'importe quelle stack Jenkins/GitLab/Ansible.
 *
 * Prérequis:
 *   - Java 17+
 *   - tn5250j installé (https://tn5250j.sourceforge.net/)
 *   - OculiX API 4.0.0 (voir README pour le lien de téléchargement)
 *   - Compte gratuit sur https://pub400.com
 *
 * Variables d'environnement:
 *   TN5250J_BAT      chemin du script de lancement tn5250j (obligatoire)
 *   PUB400_USER      votre nom d'utilisateur PUB400 (obligatoire)
 *   PUB400_PASSWORD  votre mot de passe PUB400 (obligatoire pour login effectif)
 *
 * Compile et run: voir README.md.
 */
public class DemoOculixMainframe {

    private static final String LAUNCHER_BAT = env("TN5250J_BAT");
    private static final String USER         = env("PUB400_USER");
    private static final String PWD          = System.getenv("PUB400_PASSWORD");

    private static final String CONNECTION_IMG = "assets/AS400_Connection.png";
    private static final String WELCOME_IMG    = "assets/welcompub.png";
    private static final String LOGGED_IN_IMG  = "assets/mainmenu.png";

    private static final int    LAUNCH_WAIT_SEC = 15;
    private static final int    TASKBAR_PX      = 48;
    private static final String VIDEO_OUT       = "demo_pub400_run.mkv";

    // Chronomètre inter-étapes
    private static long tStart, tLast;
    private static Process ffmpeg;

    private static String env(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            System.err.println("Missing env var: " + key + " (see README.md)");
            System.exit(1);
        }
        return v;
    }

    private static void step(String label) {
        long now = System.currentTimeMillis();
        System.out.printf("[TIMING] %-40s | step=%6d ms | total=%6d ms%n",
                label, now - tLast, now - tStart);
        tLast = now;
    }

    /** Démarre l'enregistrement vidéo (ffmpeg optionnel, silencieux si absent). */
    private static void startRecording() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-y",
                "-f", "gdigrab",
                "-framerate", "15",
                "-i", "desktop",
                "-vf", "crop=iw:ih-60:0:0",   // crop 60 px du bas (taskbar Windows)
                "-c:v", "libx264",
                "-preset", "ultrafast",
                "-crf", "28",
                "-pix_fmt", "yuv420p",
                VIDEO_OUT
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(new java.io.File("ffmpeg.log"));
        try {
            ffmpeg = pb.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { stopRecording(); } catch (Exception ignored) {}
            }));
        } catch (IOException e) {
            System.out.println("[Demo] ffmpeg introuvable dans PATH — recording skippé");
        }
    }

    private static void stopRecording() throws Exception {
        if (ffmpeg == null || !ffmpeg.isAlive()) return;
        ffmpeg.getOutputStream().write('q');
        ffmpeg.getOutputStream().flush();
        ffmpeg.getOutputStream().close();
        ffmpeg.waitFor();
    }

    public static void main(String[] args) throws Exception {
        tStart = tLast = System.currentTimeMillis();
        Screen s = new Screen();
        step("Screen init (JVM + OpenCV + Legerix load)");

        App.open(LAUNCHER_BAT);
        step("App.open(tn5250j)");

        sleepQuietly(2000);
        step("sleep 2000ms (laisse tn5250j apparaître)");

        startRecording();
        step("ffmpeg recording started → " + VIDEO_OUT);

        s.click(CONNECTION_IMG);
        step("s.click(CONNECTION_IMG)");

        try {
            Match banner = s.wait(WELCOME_IMG, LAUNCH_WAIT_SEC);
            step("s.wait(WELCOME_IMG) matched at " + banner.getTarget());

            s.type(USER);
            step("s.type(user) [" + USER.length() + " chars]");

            s.type(Key.TAB);
            step("s.type(TAB)");

            if (PWD == null || PWD.isBlank()) {
                step("PUB400_PASSWORD not set — skipping login");
            } else {
                boolean prevActionLogs = org.sikuli.basics.Settings.ActionLogs;
                boolean prevInfoLogs   = org.sikuli.basics.Settings.InfoLogs;
                org.sikuli.basics.Settings.ActionLogs = false;
                org.sikuli.basics.Settings.InfoLogs   = false;
                try {
                    s.type(PWD);
                } finally {
                    org.sikuli.basics.Settings.ActionLogs = prevActionLogs;
                    org.sikuli.basics.Settings.InfoLogs   = prevInfoLogs;
                }
                step("s.type(password) [" + PWD.length() + " chars, silenced]");

                s.type(Key.ENTER);
                step("s.type(ENTER)");
            }
        } catch (FindFailed e) {
            step("s.wait(WELCOME_IMG) FAILED — no login attempted");
        }

        // Fast-path via observe: si l'image post-login apparaît, le login est
        // prouvé — pas besoin de capture séparée, l'image trouvée EST la preuve.
        s.onAppear(LOGGED_IN_IMG);
        boolean loggedIn = s.observe(5);
        if (loggedIn) {
            step("Login confirmé (observe → IBM i Main Menu détecté)");
        } else {
            step("Login pas confirmé — fallback sleep + capture debug");
            sleepQuietly(3000);
            Region cropped = new Region(0, 0, s.w, s.h - TASKBAR_PX);
            String path = cropped.getScreen().capture(cropped).save(".", "debug.png");
            step("capture debug PNG → " + path);
        }

        stopRecording();
        step("ffmpeg recording stopped + finalized");

        System.out.printf("%n[TIMING] === TOTAL === %d ms (%.2f s)%n",
                System.currentTimeMillis() - tStart,
                (System.currentTimeMillis() - tStart) / 1000.0);
    }

    private static void sleepQuietly(int millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
