package com.mundymorning.pictureshop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 11;
    private static final int CREATE_PNG = 12;
    private EditorView editor;
    private TextView hint;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(22, 24, 31));
        getWindow().setNavigationBarColor(Color.rgb(22, 24, 31));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(22, 24, 31));

        TextView title = new TextView(this);
        title.setText("PICTURESHOP");
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(16), dp(8), dp(12), dp(8));
        title.setTypeface(null, 1);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));

        HorizontalScrollView strip = new HorizontalScrollView(this);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout tools = new LinearLayout(this);
        tools.setGravity(Gravity.CENTER_VERTICAL);
        tools.setPadding(dp(7), dp(4), dp(7), dp(4));
        strip.addView(tools);
        root.addView(strip, new LinearLayout.LayoutParams(-1, dp(54)));

        editor = new EditorView(this);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        hint = new TextView(this);
        hint.setText("Open an image to begin. Your original stays full resolution.");
        hint.setTextColor(Color.rgb(225, 228, 235));
        hint.setTextSize(13);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(14), 0, dp(14), 0);
        root.addView(hint, new LinearLayout.LayoutParams(-1, dp(38)));

        addTool(tools, "Open", v -> openImage());
        addTool(tools, "−", v -> editor.zoomBy(0.8f));
        addTool(tools, "+", v -> editor.zoomBy(1.25f));
        addTool(tools, "Pan", v -> { editor.setMode(EditorView.Mode.PAN); updateHint("Pan mode: drag the image. Use + / − to zoom."); });
        addTool(tools, "Lasso", v -> { editor.setMode(EditorView.Mode.LASSO); updateHint("Lasso mode: tap around the object, then tap Finish."); });
        addTool(tools, "Undo pt", v -> editor.undoPoint());
        addTool(tools, "Finish", v -> { editor.finishLasso(); updateHint("Outline closed. Tap Cut Layer to create a transparent cutout."); });
        addTool(tools, "Cut Layer", v -> { if (editor.cutSelection()) updateHint("Cutout added as a movable layer. Choose Move Layer to reposition it."); });
        addTool(tools, "Move Layer", v -> { editor.setMode(EditorView.Mode.MOVE); updateHint("Move Layer mode: drag the cutout."); });
        addTool(tools, "Export PNG", v -> createPng());

        setContentView(root);
    }

    private void addTool(LinearLayout row, String label, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(12);
        b.setAllCaps(false);
        b.setMinWidth(dp(58));
        b.setPadding(dp(8), 0, dp(8), 0);
        row.addView(b, new LinearLayout.LayoutParams(-2, dp(44)));
        b.setOnClickListener(action);
    }

    private void openImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("image/*");
        i.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i, PICK_IMAGE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_IMAGE) {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap image = BitmapFactory.decodeStream(in, null, opts);
                if (image == null) throw new IllegalArgumentException("Unsupported image");
                editor.setImage(image);
                updateHint("Image loaded at original resolution. Pinch or use + / −, then trace a cutout.");
            } catch (Exception e) { toast("Could not open image: " + e.getMessage()); }
        } else if (requestCode == CREATE_PNG) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Could not create file");
                editor.exportComposite().compress(Bitmap.CompressFormat.PNG, 100, out);
                toast("PNG exported.");
            } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
        }
    }

    private void createPng() {
        if (!editor.hasImage()) { toast("Open an image first."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_TITLE, "pictureshop-export.png");
        startActivityForResult(i, CREATE_PNG);
    }
    private void updateHint(String text) { hint.setText(text); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
