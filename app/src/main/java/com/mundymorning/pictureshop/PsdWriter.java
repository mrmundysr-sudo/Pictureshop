package com.mundymorning.pictureshop;

import android.graphics.Bitmap;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Writes a basic layered, 8-bit RGB PSD with RGBA pixel channels and a merged preview. */
final class PsdWriter {
    static final class Layer {
        final String name; final Bitmap bitmap; final int x,y; final boolean visible;
        Layer(String name, Bitmap bitmap, int x, int y, boolean visible) {
            this.name=name; this.bitmap=bitmap; this.x=x; this.y=y; this.visible=visible;
        }
    }

    static void write(OutputStream stream, List<Layer> layers, Bitmap composite) throws IOException {
        int width=composite.getWidth(), height=composite.getHeight();
        if(width<=0 || height<=0 || layers.isEmpty()) throw new IOException("Invalid document dimensions.");
        DataOutputStream out=new DataOutputStream(stream);
        // PSD header
        out.writeBytes("8BPS"); out.writeShort(1); out.write(new byte[6]);
        out.writeShort(4); out.writeInt(height); out.writeInt(width); out.writeShort(8); out.writeShort(3);
        out.writeInt(0); // color mode data
        out.writeInt(0); // image resources

        long layerInfoLength=2;
        for(Layer layer:layers) {
            int nameBytes=nameBytes(layer.name);
            int nameField=round4(1+nameBytes);
            long channels=4L*(6L+2L+(long)layer.bitmap.getWidth()*layer.bitmap.getHeight());
            layerInfoLength += 66L+nameField+channels;
        }
        if((layerInfoLength&1L)!=0) layerInfoLength++;
        long layerMaskLength=4L+layerInfoLength+4L;
        if(layerInfoLength>0x7fffffffL || layerMaskLength>0x7fffffffL) throw new IOException("PSD exceeds supported size.");
        out.writeInt((int)layerMaskLength);
        out.writeInt((int)layerInfoLength);
        out.writeShort(layers.size());

        // Layer records; PSD stores the topmost layer first.
        for(Layer layer:layers) writeLayerRecord(out,layer);
        // Channel image data follows all layer records, in matching record order.
        for(Layer layer:layers) writeLayerChannels(out,layer);

        long actualInfo=layerInfoLength;
        // Data was sized in advance. An odd padded layer-info length has one zero pad byte.
        // Channel/record sizes are even for normal image dimensions; write it when needed.
        if((actualInfo&1L)!=0) out.writeByte(0);
        out.writeInt(0); // global layer mask info
        out.writeShort(0); // composite image data compression: raw
        writeCompositeChannels(out,composite);
        out.flush();
    }

    private static void writeLayerRecord(DataOutputStream out, Layer layer) throws IOException {
        int w=layer.bitmap.getWidth(), h=layer.bitmap.getHeight();
        out.writeInt(layer.y); out.writeInt(layer.x); out.writeInt(layer.y+h); out.writeInt(layer.x+w);
        out.writeShort(4);
        int[] ids={0,1,2,-1};
        int channelLen=2+w*h;
        for(int id:ids) { out.writeShort(id); out.writeInt(channelLen); }
        out.writeBytes("8BIM"); out.writeBytes("norm");
        out.writeByte(255); out.writeByte(0); out.writeByte(layer.visible?0:2); out.writeByte(0);
        byte[] name=layer.name.getBytes(StandardCharsets.ISO_8859_1);
        int nameField=round4(1+name.length);
        int extra=8+nameField;
        out.writeInt(extra); out.writeInt(0); out.writeInt(0);
        out.writeByte(name.length); out.write(name);
        for(int i=1+name.length;i<nameField;i++) out.writeByte(0);
    }

    private static void writeLayerChannels(DataOutputStream out, Layer layer) throws IOException {
        Bitmap b=layer.bitmap;
        int w=b.getWidth(), h=b.getHeight(), n=w*h;
        int[] pixels=new int[n]; b.getPixels(pixels,0,w,0,0,w,h);
        int[] shifts={16,8,0,24};
        byte[] plane=new byte[n];
        for(int shift:shifts) {
            out.writeShort(0); // channel compression: raw
            for(int i=0;i<n;i++) plane[i]=(byte)(pixels[i]>>>shift);
            out.write(plane);
        }
    }

    private static void writeCompositeChannels(DataOutputStream out, Bitmap b) throws IOException {
        int w=b.getWidth(), h=b.getHeight(), n=w*h;
        int[] pixels=new int[n]; b.getPixels(pixels,0,w,0,0,w,h);
        int[] shifts={16,8,0,24};
        byte[] plane=new byte[n];
        for(int shift:shifts) {
            for(int i=0;i<n;i++) plane[i]=(byte)(pixels[i]>>>shift);
            out.write(plane);
        }
    }

    private static int nameBytes(String value) {
        return value.getBytes(StandardCharsets.ISO_8859_1).length;
    }
    private static int round4(int value) { return (value+3)&~3; }
    private PsdWriter() {}
}
