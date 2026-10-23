package com.mundymorning.pictureshop;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int PICK_IMAGE = 11;
    private static final int CREATE_PNG = 12;
    private static final int CREATE_PSD = 13;
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

        LinearLayout basic = addRow(root);
        addTool(basic, "Open", v -> openImage());
        addTool(basic, "Zoom −", v -> editor.zoomBy(0.8f));
        addTool(basic, "Zoom +", v -> editor.zoomBy(1.25f));
        addTool(basic, "Pan", v -> {
            editor.setMode(EditorView.Mode.PAN);
            updateHint("Pan mode: drag the image. Zoom with the buttons or two fingers.");
        });

        LinearLayout trace = addRow(root);
        addTool(trace, "Lasso", v -> {
            editor.setMode(EditorView.Mode.LASSO);
            updateHint("Step 1: trace the edge with taps or a finger drag.");
        });
        addTool(trace, "Undo Point", v -> {
            if (editor.getLassoPointCount() > 0) {
                editor.undoPoint();
                updateHint("Last lasso point removed.");
            } else updateHint("There are no lasso points to undo.");
        });
        addTool(trace, "Finish Lasso", v -> {
            if (editor.finishLasso()) updateHint("Step 2: outline closed. Tap Cut Layer.");
            else updateHint("Add at least 3 points around the object, then tap Finish Lasso.");
        });
        addTool(trace, "Cut Layer", v -> cutLayer());

        LinearLayout export = addRow(root);
        addTool(export, "Move Layer", v -> {
            if (editor.hasCutouts()) {
                editor.setMode(EditorView.Mode.MOVE);
                updateHint("Move mode: drag the cutout on the canvas.");
            } else updateHint("No cutout layer yet. Finish the lasso, then tap Cut Layer.");
        });
        addTool(export, "Export PSD", v -> createPsd());
        addTool(export, "Export PNG", v -> createPng());

        editor = new EditorView(this);
        root.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        hint = new TextView(this);
        hint.setText("Open image → Lasso → Finish Lasso → Cut Layer → Export PSD");
        hint.setTextColor(Color.rgb(245, 235, 190));
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(12), dp(4), dp(12), dp(4));
        root.addView(hint, new LinearLayout.LayoutParams(-1, dp(48)));

        setContentView(root);
    }

    private LinearLayout addRow(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(3), dp(2), dp(3), dp(2));
        root.addView(row, new LinearLayout.LayoutParams(-1, dp(48)));
        return row;
    }

    private void addTool(LinearLayout row, String label, View.OnClickListener action) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        row.addView(b, lp);
        b.setOnClickListener(action);
    }

    private void cutLayer() {
        if (editor.cutSelection()) {
            updateHint("Cutout created as a layer. Drag it to move, or export the PSD.");
        } else if (!editor.hasImage()) {
            updateHint("Open an image first.");
        } else if (editor.getLassoPointCount() < 3) {
            updateHint("Trace the object with at least 3 points, then tap Finish Lasso.");
        } else if (!editor.isLassoFinished()) {
            updateHint("Tap Finish Lasso to close the outline before cutting.");
        } else {
            updateHint("Could not cut that outline. Try tracing inside the image.");
        }
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
                updateHint("Image opened at full resolution. Lasso → Finish Lasso → Cut Layer.");
            } catch (Exception e) { toast("Could not open image: " + e.getMessage()); }
        } else if (requestCode == CREATE_PNG) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Could not create file");
                editor.exportComposite().compress(Bitmap.CompressFormat.PNG, 100, out);
                toast("PNG exported.");
            } catch (Exception e) { toast("Export failed: " + e.getMessage()); }
        } else if (requestCode == CREATE_PSD) {
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("Could not create file");
                editor.writePsd(out);
                toast("Layered PSD exported. Open it in Photoshop to check your layers.");
            } catch (Exception e) { toast("PSD export failed: " + e.getMessage()); }
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

    private void createPsd() {
        if (!editor.hasImage()) { toast("Open an image first."); return; }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/vnd.adobe.photoshop");
        i.putExtra(Intent.EXTRA_TITLE, "pictureshop-project.psd");
        startActivityForResult(i, CREATE_PSD);
    }

    private void updateHint(String text) { hint.setText(text); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}

class DeliberateBreak { int x = "not an int"; }
