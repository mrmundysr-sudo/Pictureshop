package com.mundymorning.pictureshop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.io.OutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class EditorView extends View {
    enum Mode { PAN, LASSO, MOVE }
    private static class Layer {
        Bitmap bitmap; float x, y; boolean visible = true; String name;
        final float cutX, cutY;      // where the cutout was taken from (never changes)
        final float[][] path;        // exact lasso points, original image coordinates
        Layer(Bitmap b, float px, float py, String n, float[][] lassoPath) {
            bitmap=b; x=px; y=py; cutX=px; cutY=py; name=n; path=lassoPath;
        }
    }

    /** Read-only snapshot of a layer for the layer panel. id 0 = background, 1.. = cutouts (bottom to top). */
    public static class LayerInfo {
        public final int id; public final String name; public final boolean visible, selected, background;
        LayerInfo(int id, String name, boolean visible, boolean selected) {
            this.id=id; this.name=name; this.visible=visible; this.selected=selected; this.background=(id==0);
        }
    }
    public interface LayersListener { void onLayersChanged(); }
    private static final int MAX_NAME = 100;
    private LayersListener layersListener;
    private String backgroundName = "Background";
    private boolean backgroundVisible = true;
    private Bitmap image;
    private final List<Layer> layers = new ArrayList<>();
    private final List<float[]> points = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Mode mode = Mode.PAN;
    private float fit=1f, zoom=1f, offsetX, offsetY, downX, downY, lastLassoX, lastLassoY;
    private boolean dragging, lassoFinished;
    private ScaleGestureDetector scaleDetector;
    private int selectedLayer = -1;

    public EditorView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        setBackgroundColor(Color.rgb(42,45,54));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(3f);
        line.setColor(Color.rgb(34, 210, 170));
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        shade.setColor(Color.argb(46, 28, 228, 183));
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom = clamp(zoom * detector.getScaleFactor(), 0.35f, 8f);
                invalidate(); return true;
            }
        });
    }

    public void setImage(Bitmap b) {
        image = b.copy(Bitmap.Config.ARGB_8888, true);
        layers.clear(); points.clear(); selectedLayer=-1; lassoFinished=false;
        backgroundName="Background"; backgroundVisible=true;
        zoom=1f; offsetX=0; offsetY=0; fit=1f;
        invalidate(); notifyLayers();
    }
    public void setLayersListener(LayersListener l) { layersListener=l; }
    private void notifyLayers() { if (layersListener!=null) layersListener.onLayersChanged(); }
    public String getDefaultCutoutName() { return "Cutout "+(layers.size()+1); }
    public boolean canCut() { return image!=null && points.size()>=3 && lassoFinished; }

    /** Layers for the panel, topmost first, background last. */
    public List<LayerInfo> getLayerInfos() {
        List<LayerInfo> out=new ArrayList<>();
        for (int i=layers.size()-1;i>=0;i--) {
            Layer l=layers.get(i);
            out.add(new LayerInfo(i+1,l.name,l.visible,selectedLayer==i));
        }
        if (image!=null) out.add(new LayerInfo(0,backgroundName,backgroundVisible,selectedLayer<0));
        return out;
    }
    public void selectLayer(int id) {
        if (id<0 || id>layers.size()) return;
        selectedLayer=id-1; invalidate(); notifyLayers();
    }
    public void setLayerVisible(int id, boolean visible) {
        if (id==0) backgroundVisible=visible;
        else if (id>0 && id<=layers.size()) layers.get(id-1).visible=visible;
        else return;
        invalidate(); notifyLayers();
    }
    /** Returns false (and changes nothing) when the new name is blank. */
    public boolean renameLayer(int id, String name) {
        String n=cleanName(name);
        if (n.isEmpty()) return false;
        if (id==0) backgroundName=n;
        else if (id>0 && id<=layers.size()) layers.get(id-1).name=n;
        else return false;
        invalidate(); notifyLayers(); return true;
    }
    private static String cleanName(String name) {
        if (name==null) return "";
        String n=name.replaceAll("[\\r\\n\\t]+"," ").trim();
        return n.length()>MAX_NAME ? n.substring(0,MAX_NAME).trim() : n;
    }
    public boolean hasImage() { return image != null; }
    public boolean hasCutouts() { return !layers.isEmpty(); }
    public int getLassoPointCount() { return points.size(); }
    public boolean isLassoFinished() { return lassoFinished; }
    public void setMode(Mode m) { mode=m; invalidate(); }
    public void zoomBy(float factor) { zoom=clamp(zoom*factor,0.35f,8f); invalidate(); }
    public void undoPoint() {
        if (!points.isEmpty()) points.remove(points.size()-1);
        lassoFinished=false; invalidate();
    }

    public boolean finishLasso() {
        if (points.size()<3) return false;
        lassoFinished=true; invalidate(); return true;
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (image == null) {
            paint.setColor(Color.LTGRAY); paint.setTextSize(18);
            c.drawText("Open an image to start", 24, getHeight()/2f, paint); return;
        }
        fit = Math.min((getWidth()-24f)/image.getWidth(), (getHeight()-24f)/image.getHeight());
        if (fit <= 0) fit=1;
        float s=fit*zoom;
        c.save();
        c.translate(getWidth()/2f + offsetX, getHeight()/2f + offsetY);
        c.scale(s,s);
        c.translate(-image.getWidth()/2f,-image.getHeight()/2f);
        if (backgroundVisible) c.drawBitmap(image,0,0,paint);
        for (Layer layer: layers) if(layer.visible) c.drawBitmap(layer.bitmap, layer.x, layer.y, paint);
        if (!points.isEmpty()) {
            Path p=new Path();
            p.moveTo(points.get(0)[0],points.get(0)[1]);
            for(int i=1;i<points.size();i++) p.lineTo(points.get(i)[0],points.get(i)[1]);
            if(lassoFinished) {
                p.close();
                c.drawPath(p,shade);
            }
            line.setColor(lassoFinished?Color.rgb(50,255,201):Color.rgb(42,235,188));
            c.drawPath(p,line);
            line.setStyle(Paint.Style.FILL);
            for(float[] pt:points) c.drawCircle(pt[0],pt[1],Math.max(3,5/s),line);
            line.setStyle(Paint.Style.STROKE);
        }
        c.restore();
        paint.setColor(Color.WHITE); paint.setTextSize(14);
        String label=mode==Mode.LASSO?"LASSO • tap points or trace":mode==Mode.MOVE?"MOVE LAYER":"PAN";
        c.drawText(label, 12, 22, paint);
        if(!layers.isEmpty()) c.drawText("Layers: "+(layers.size()+1)+" • cutouts: "+layers.size(),12,42,paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        float x=e.getX(), y=e.getY();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=x; downY=y; dragging=false;
                if(mode==Mode.LASSO && image!=null) {
                    float[] p=toImage(x,y);
                    points.add(p); lastLassoX=x; lastLassoY=y; lassoFinished=false;
                    invalidate(); return true;
                }
                if(mode==Mode.MOVE && !layers.isEmpty()) {
                    if(selectedLayer<0) { selectedLayer=layers.size()-1; notifyLayers(); }
                    dragging=true; return true;
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>1) return true;
                if(mode==Mode.PAN) {
                    offsetX += x-downX; offsetY += y-downY; downX=x; downY=y; invalidate();
                } else if(mode==Mode.LASSO && image!=null) {
                    float dx=x-lastLassoX, dy=y-lastLassoY;
                    if(dx*dx+dy*dy>=64f) {
                        points.add(toImage(x,y)); lastLassoX=x; lastLassoY=y; invalidate();
                    }
                } else if(mode==Mode.MOVE && dragging && selectedLayer>=0) {
                    float s=fit*zoom;
                    layers.get(selectedLayer).x += (x-downX)/s;
                    layers.get(selectedLayer).y += (y-downY)/s;
                    downX=x; downY=y; invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                dragging=false; return true;
        }
        return true;
    }

    private float[] toImage(float x,float y) {
        float s=fit*zoom;
        return new float[]{(x-getWidth()/2f-offsetX)/s+image.getWidth()/2f,
                (y-getHeight()/2f-offsetY)/s+image.getHeight()/2f};
    }

    public boolean cutSelection() { return cutSelection(null); }

    /** Cuts the finished lasso into a new layer. A blank name falls back to "Cutout N". */
    public boolean cutSelection(String requestedName) {
        if(image==null || points.size()<3 || !lassoFinished) return false;
        float minX=image.getWidth(), minY=image.getHeight(), maxX=0,maxY=0;
        Path path=new Path(); path.moveTo(points.get(0)[0],points.get(0)[1]);
        for(float[] p:points) { minX=Math.min(minX,p[0]); minY=Math.min(minY,p[1]); maxX=Math.max(maxX,p[0]); maxY=Math.max(maxY,p[1]); }
        for(int i=1;i<points.size();i++) path.lineTo(points.get(i)[0],points.get(i)[1]);
        path.close();
        int left=Math.max(0,(int)Math.floor(minX)), top=Math.max(0,(int)Math.floor(minY));
        int right=Math.min(image.getWidth(),(int)Math.ceil(maxX)), bottom=Math.min(image.getHeight(),(int)Math.ceil(maxY));
        if(right<=left || bottom<=top) return false;
        Bitmap cut=Bitmap.createBitmap(right-left,bottom-top,Bitmap.Config.ARGB_8888);
        Canvas cc=new Canvas(cut); cc.translate(-left,-top); cc.clipPath(path); cc.drawBitmap(image,0,0,paint);
        Paint clear=new Paint(Paint.ANTI_ALIAS_FLAG);
        clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        new Canvas(image).drawPath(path,clear);
        float[][] lassoPath=new float[points.size()][];
        for(int i=0;i<lassoPath.length;i++) lassoPath[i]=new float[]{points.get(i)[0],points.get(i)[1]};
        String name=cleanName(requestedName);
        if(name.isEmpty()) name=getDefaultCutoutName();
        layers.add(new Layer(cut,left,top,name,lassoPath)); selectedLayer=layers.size()-1;
        points.clear(); lassoFinished=false; mode=Mode.MOVE; invalidate(); notifyLayers(); return true;
    }

    public Bitmap exportComposite() {
        Bitmap out=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out); if(backgroundVisible) c.drawBitmap(image,0,0,paint);
        for(Layer layer:layers) if(layer.visible) c.drawBitmap(layer.bitmap,layer.x,layer.y,paint);
        return out;
    }

    public void writePsd(OutputStream out) throws java.io.IOException {
        if(image==null) throw new java.io.IOException("No image is open.");
        ArrayList<PsdWriter.Layer> psdLayers=new ArrayList<>();
        for(int i=layers.size()-1;i>=0;i--) {
            Layer l=layers.get(i);
            psdLayers.add(new PsdWriter.Layer(l.name,l.bitmap,Math.round(l.x),Math.round(l.y),l.visible));
        }
        psdLayers.add(new PsdWriter.Layer(backgroundName,image,0,0,backgroundVisible));
        PsdWriter.write(out,psdLayers,exportComposite());
    }

    /** Human-readable JSON with the exact geometry of every layer (background first, then cutouts bottom to top). */
    public String exportLayersJson() throws JSONException {
        if(image==null) throw new JSONException("No image is open.");
        JSONObject root=new JSONObject();
        root.put("format","pictureshop-layers");
        root.put("version",1);
        root.put("coordinateSystem","pixels, origin at top-left of the original image");
        JSONObject canvas=new JSONObject();
        canvas.put("width",image.getWidth()); canvas.put("height",image.getHeight());
        root.put("canvas",canvas);
        JSONArray arr=new JSONArray();
        int w=image.getWidth(), h=image.getHeight();
        JSONObject bg=new JSONObject();
        bg.put("index",0); bg.put("type","background"); bg.put("name",backgroundName);
        bg.put("visible",backgroundVisible);
        bg.put("position",xy(0,0));
        bg.put("size",size(w,h));
        JSONArray bgPath=new JSONArray();
        bgPath.put(xy(0,0)); bgPath.put(xy(w,0)); bgPath.put(xy(w,h)); bgPath.put(xy(0,h));
        bg.put("path",bgPath);
        arr.put(bg);
        for(int i=0;i<layers.size();i++) {
            Layer l=layers.get(i);
            JSONObject o=new JSONObject();
            o.put("index",i+1); o.put("type","cutout"); o.put("name",l.name);
            o.put("visible",l.visible);
            o.put("position",xy(l.x,l.y));
            o.put("originalPosition",xy(l.cutX,l.cutY));
            o.put("size",size(l.bitmap.getWidth(),l.bitmap.getHeight()));
            JSONArray pts=new JSONArray();
            for(float[] p:l.path) pts.put(xy(p[0],p[1]));
            o.put("path",pts);
            arr.put(o);
        }
        root.put("layers",arr);
        return root.toString(2);
    }
    private static JSONObject xy(float x,float y) throws JSONException {
        JSONObject o=new JSONObject(); o.put("x",exact(x)); o.put("y",exact(y)); return o;
    }
    private static JSONObject size(int w,int h) throws JSONException {
        JSONObject o=new JSONObject(); o.put("width",w); o.put("height",h); return o;
    }
    /** Float to double without widening noise (12.3f becomes 12.3, not 12.300000190734863). */
    private static double exact(float v) { return Double.parseDouble(Float.toString(v)); }

    private float clamp(float v,float lo,float hi){ return Math.max(lo,Math.min(hi,v)); }
}
