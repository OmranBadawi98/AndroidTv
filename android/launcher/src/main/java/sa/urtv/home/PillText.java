package sa.urtv.home;

/**
 * What the home screen's pills say, with no Android in it (tests/PillTextRun.java runs it). A pill shows its icon only; the
 * focused one widens to show its name (Pill.java), so the text a pill has and the text it shows are two things: this is both.
 */
final class PillText {
    private PillText() {}

    /** the words on screen: the name while the pill has the focus, nothing (icon only) otherwise */
    static String shown(String label, boolean focused) { return focused ? label : ""; }

    /** the screensaver pill's name: OK starts the screensaver, a long OK opens its settings, and the focused pill says so */
    static final String SAVER = "شاشة التوقف · مطوّلًا: الإعدادات";

    /** the network pill's name: the cable, the Wi-Fi network by name when it can be read (else «واي فاي»), or «غير متصل» */
    static String network(boolean wired, boolean wifi, String ssid) {
        if (wired) return "شبكة سلكية";
        if (!wifi) return "غير متصل";
        String s = ssid == null ? "" : ssid.replace("\"", "");
        return s.isEmpty() || s.contains("unknown ssid") ? "واي فاي" : s;
    }

    /** 0-9 as ٠-٩, the digits the whole home screen uses */
    static String digits(String s) {
        return DigitsText.convert(s, false);
    }
}
