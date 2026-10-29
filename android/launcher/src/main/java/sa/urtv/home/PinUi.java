package sa.urtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

/** The PIN keypad's TV colours, dp sizing and a visible remote-focus state (from AbdullahCg's PR #40). */
final class PinUi {
    static final int BG=0xF2091912,SURFACE=0x16FFFFFF,GOLD=0xFFD6AF6B,TEXT=0xFFF6EFE0,MUTED=0xFFB9C3B8;
    static final int FOCUS=0xFFF6EFE0,INK=0xFF04120C;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable panel(Context c,int color,int border){
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(c,14));g.setStroke(dp(c,1),border);return g;
    }
    static TextView text(Context c,String value,int size,int color){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);t.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        t.setTypeface(Fonts.get(c,(size>=25?"ThmanyahDisplay-Bold":"Thmanyah-Regular")+".otf"));return t;
    }
    static Button button(Context c,String label){
        Button b=new Button(c);b.setId(View.generateViewId());b.setText(label);b.setAllCaps(false);
        b.setTextSize(21);b.setTypeface(Fonts.get(c,"Thmanyah-Medium.otf"));b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c,16),dp(c,8),dp(c,16),dp(c,8));
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);
        b.setBackgroundTintList(null);b.setStateListAnimator(null);
        int[][] states={{android.R.attr.state_focused},{android.R.attr.state_pressed},{}};
        b.setTextColor(new ColorStateList(states,new int[]{INK,INK,TEXT}));
        StateListDrawable bg=new StateListDrawable();
        bg.addState(states[0],panel(c,FOCUS,FOCUS));bg.addState(states[1],panel(c,FOCUS,FOCUS));
        bg.addState(states[2],panel(c,SURFACE,0x26FFFFFF));b.setBackground(bg);
        b.setOnFocusChangeListener((v,on)->v.animate().scaleX(on?1.035f:1f).scaleY(on?1.035f:1f).setDuration(130).start());return b;
    }
}
