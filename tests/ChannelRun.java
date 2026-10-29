package sa.urtv.screensaver;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** For tests/channel_test.py: every asset URL of the built page is under Channel.BASE, which only the app answers, and none is GitHub's. */
public final class ChannelRun {
    static int failed;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
        if (!ok) failed++;
    }

    public static void main(String[] args) throws Exception {
        String page = new String(Files.readAllBytes(Paths.get(args[0])), "UTF-8");
        Matcher m = Pattern.compile(Pattern.quote(Channel.BASE) + "(assets/fonts/|assets/backgrounds/)[A-Za-z0-9_.-]+").matcher(page);
        int n = 0;
        while (m.find()) n++;
        check(n + " asset URLs of the page under " + Channel.BASE, n > 0, "none");
        check("the address never resolves: only the app answers it", Channel.BASE.startsWith("https://") && Channel.BASE.endsWith(".invalid/"), Channel.BASE);
        check("nothing in the page points at GitHub", !page.contains("githubusercontent.com") && !page.contains("tshi-dev"), "");
        System.exit(failed == 0 ? 0 : 1);
    }
}
