package sa.urtv.screensaver;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

import java.util.List;

/**
 * Hands the launcher (same signing key, see READ_CONFIG in the manifest) what its home-screen card needs:
 * the page settings as a URL fragment, which card to show ("none" = off) and whether the greeting is on ("1"/"0").
 *
 * «المهام»: content://sa.urtv.screensaver.config/tasks lists the tasks (i from 1, t, done, at). The launcher
 * may also change them, which needs WRITE_TASKS (signature, same key as READ_CONFIG):
 *   insert  ContentValues t = the text               -> .../tasks/N, or null when refused (empty, full)
 *   update  ContentValues id = ID (or i = N)         -> 1 when that task is marked done
 *   delete  .../tasks/id/ID (or .../tasks/N)         -> 1 when that task is gone
 * N is the place in the list, which moves when the list does; id is the task's own and stays (the screens use it, `list` shows N).
 *
 * «صوري»: the owner's own photos (Own). query .../own gives n (how many), mode (mix|only) and rev (changes whenever
 * the photos do); openFile .../own/N opens photo N (1..n) read-only, which READ_CONFIG allows the launcher. Nothing
 * else can be read or written there. .../own-in takes the next photo from the shell (`content write`), which the
 * manifest lets in with DUMP, as for SaverReceiver; SaverReceiver PHOTOS then checks and keeps it.
 */
public class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        Prefs p = new Prefs(getContext());
        if (isOwn(uri)) {
            MatrixCursor o = new MatrixCursor(new String[] {"n", "mode", "rev"});
            o.addRow(new Object[] {Own.count(getContext()), Own.mode(getContext()), p.ownRev()});
            return o;
        }
        if (isTasks(uri)) {
            MatrixCursor t = new MatrixCursor(new String[] {"i", "t", "done", "at", "id"});
            List<Task> all = p.tasks();
            for (int k = 0; k < all.size(); k++) {
                Task x = all.get(k);
                t.addRow(new Object[] {k + 1, x.text, x.done ? 1 : 0, x.at, x.id});
            }
            return t;
        }
        // home_panel: the first card alone (what an older launcher reads); home_panels: all of them, "a,b,c" or "none"
        List<String> cards = p.homePanels();
        MatrixCursor c = new MatrixCursor(new String[] {"fragment", "home_panel", "greeting", "home_panels", "adhan_bar", "wifi_pill"});
        c.addRow(new Object[] {p.fragment(), cards.isEmpty() ? "none" : cards.get(0), p.greeting() ? "1" : "0", p.homePanel(), p.adhanBar() ? "1" : "0", p.wifiPill() ? "1" : "0"});
        return c;
    }

    @Override public String getType(Uri uri) { return null; }

    private static boolean isOwn(Uri uri) {
        return uri != null && uri.getPathSegments().size() == 1 && "own".equals(uri.getPathSegments().get(0));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context c = getContext();
        java.util.List<String> seg = uri.getPathSegments();
        if (seg.size() == 2 && "own".equals(seg.get(0)) && "r".equals(mode)) {
            int n;
            try { n = Integer.parseInt(seg.get(1)); } catch (NumberFormatException e) { throw new FileNotFoundException(); }
            if (n < 1 || n > Own.count(c)) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(Own.file(c, n), ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if (seg.size() == 1 && "own-in".equals(seg.get(0)) && mode.contains("w") && !mode.contains("a")) {
            File dir = Own.dir(c);
            if (!dir.isDirectory() && !dir.mkdirs()) throw new FileNotFoundException();
            return ParcelFileDescriptor.open(Own.incoming(c), ParcelFileDescriptor.MODE_WRITE_ONLY
                | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE);
        }
        throw new FileNotFoundException();
    }

    private static boolean isTasks(Uri uri) {
        return uri != null && uri.getPathSegments().size() >= 1 && "tasks".equals(uri.getPathSegments().get(0));
    }

    @Override
    public Uri insert(Uri uri, ContentValues v) {
        if (!isTasks(uri) || v == null) return null;
        Prefs p = new Prefs(getContext());
        if (p.addTask(v.getAsString("t")) != null) return null;
        return Uri.withAppendedPath(uri, String.valueOf(p.tasks().size()));
    }

    @Override
    public int delete(Uri uri, String s, String[] a) {
        if (!isTasks(uri) || uri.getPathSegments().size() < 2) return 0;
        List<String> seg = uri.getPathSegments();
        try {
            Prefs p = new Prefs(getContext());
            if (seg.size() == 3 && "id".equals(seg.get(1))) return p.deleteTaskId(Long.parseLong(seg.get(2))) ? 1 : 0;
            return p.deleteTask(Integer.parseInt(uri.getLastPathSegment())) ? 1 : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public int update(Uri uri, ContentValues v, String s, String[] a) {
        if (!isTasks(uri) || v == null) return 0;
        Prefs p = new Prefs(getContext());
        if (v.getAsLong("id") != null) return p.doneTaskId(v.getAsLong("id")) ? 1 : 0;
        if (v.getAsInteger("i") == null) return 0;
        return p.doneTask(v.getAsInteger("i")) ? 1 : 0;
    }
}
