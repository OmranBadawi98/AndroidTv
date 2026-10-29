package sa.urtv.screensaver;

/**
 * The address the page asks for its fonts and photos (src/build.py's REPO). .invalid never resolves, so only the app
 * answers it (PhotoCache), from the APK: nothing is fetched from GitHub.
 */
final class Channel {
    static final String BASE = "https://urtv.invalid/";

    private Channel() {}
}
