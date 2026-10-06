package com.mundymorning.pictureshop;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

public class EditorView extends View {
    enum Mode { PAN, LASSO, MOVE }
    private static class Layer {
        Bitmap bitmap; float x, y; boolean visible = true;
        Layer(Bitmap b, float px, float py) { bitmap=b; x=px; y=py; }
    }
    private Bitmap image;
    private final List<Layer> layers = new ArrayList<>();
    private final List<float[]> points = new ArrayList<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Mode mode = Mode.PAN;
    private float fit=1f, zoom=1f, offsetX, offsetY, downX, downY;
    private boolean dragging;
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
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom = clamp(zoom * detector.getScaleFactor(), 0.35f, 8f);
                invalidate(); return true;
            }
        });
    }

    public void setImage(Bitmap b) {
        image = b.copy(Bitmap.Config.ARGB_8888, true);
        layers.clear(); points.clear(); selectedLayer=-1;
        zoom=1f; offsetX=0; offsetY=0;
        fit = 1f;
        invalidate();
    }
    public boolean hasImage() { return image != null; }
    public void setMode(Mode m) { mode=m; invalidate(); }
    public void zoomBy(float factor) { zoom=clamp(zoom*factor,0.35f,8f); invalidate(); }
    public void undoPoint() { if (!points.isEmpty()) points.remove(points.size()-1); invalidate(); }
    public void finishLasso() { if(points.size()>=3) invalidate(); }

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
        c.drawBitmap(image,0,0,paint);
        for (Layer layer: layers) if(layer.visible) c.drawBitmap(layer.bitmap, layer.x, layer.y, paint);
        if (!points.isEmpty()) {
            Path p=new Path();
            p.moveTo(points.get(0)[0],points.get(0)[1]);
            for(int i=1;i<points.size();i++) p.lineTo(points.get(i)[0],points.get(i)[1]);
            if(points.size()>=3 && mode!=Mode.LASSO) p.close();
            line.setColor(Color.rgb(42,235,188));
            c.drawPath(p,line);
            line.setStyle(Paint.Style.FILL);
            for(float[] pt:points) c.drawCircle(pt[0],pt[1],Math.max(3,5/s),line);
            line.setStyle(Paint.Style.STROKE);
        }
        c.restore();
        paint.setColor(Color.WHITE); paint.setTextSize(14);
        String label=mode==Mode.LASSO?"LASSO • tap points":mode==Mode.MOVE?"MOVE LAYER":"PAN";
        c.drawText(label, 12, 22, paint);
        if(!layers.isEmpty()) c.drawText("Layers: "+(layers.size()+1)+" • cutouts: "+layers.size(),12,42,paint);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        float x=e.getX(), y=e.getY();
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX=x; downY=y; dragging=false;
                if(mode==Mode.LASSO && image!=null) { points.add(toImage(x,y)); invalidate(); return true; }
                if(mode==Mode.MOVE && !layers.isEmpty()) { selectedLayer=layers.size()-1; dragging=true; return true; }
                return true;
            case MotionEvent.ACTION_MOVE:
                if(e.getPointerCount()>1) return true;
                if(mode==Mode.PAN) {
                    offsetX += x-downX; offsetY += y-downY; downX=x; downY=y; invalidate();
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

    public boolean cutSelection() {
        if(image==null || points.size()<3) return false;
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
        Paint clear=new Paint(); clear.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR));
        new Canvas(image).drawPath(path,clear);
        layers.add(new Layer(cut,left,top)); selectedLayer=layers.size()-1;
        points.clear(); mode=Mode.MOVE; invalidate(); return true;
    }

    public Bitmap exportComposite() {
        Bitmap out=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out); c.drawBitmap(image,0,0,paint);
        for(Layer layer:layers) if(layer.visible) c.drawBitmap(layer.bitmap,layer.x,layer.y,paint);
        return out;
    }
    private float clamp(float v,float lo,float hi){ return Math.max(lo,Math.min(hi,v)); }
}
