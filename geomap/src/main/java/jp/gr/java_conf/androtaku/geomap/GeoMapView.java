package jp.gr.java_conf.androtaku.geomap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.util.AttributeSet;

import java.util.HashMap;
import java.util.List;

/**
 * Created by takuma on 2015/07/18.
 */
public class GeoMapView extends androidx.appcompat.widget.AppCompatImageView {
    private List<CountrySection> countrySections;
    private Context context;
    private Paint defaultPaint;
    private Thread prepareThread = null;
    private Thread thread = null;
    private HashMap<String, Paint> countryPaints;
    private OnInitializedListener listener;

    public GeoMapView(Context context){
        super(context);
        this.context = context;
        countryPaints = new HashMap<>();
        initialize();
    }
    public GeoMapView(Context context, AttributeSet attributeSet){
        super(context, attributeSet);
        this.context = context;
        countryPaints = new HashMap<>();
        initialize();
    }

    /**
     * initialize GeoMapView from world.svg on other thread
     */
    private void initialize(){
        defaultPaint = new Paint();
        defaultPaint.setColor(Color.BLACK);
        defaultPaint.setStyle(Paint.Style.STROKE);
        defaultPaint.setAntiAlias(true);

        final Handler handler = new Handler();

        prepareThread = new Thread(new Runnable() {
            @Override
            public void run() {
                //parse world.svg
                countrySections = SVGParser.getCountrySections(context);

                //create bitmap
                final Bitmap bitmap = Bitmap.createBitmap(GeoMapView.this.getWidth(),
                        GeoMapView.this.getHeight(), Bitmap.Config.ARGB_8888);
                //draw map on bitmap
                Canvas canvas = new Canvas(bitmap);
                drawMap(canvas);
                //run on main thread
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        GeoMapView.this.setImageBitmap(bitmap);

                        if (listener != null)
                            listener.onInitialized(GeoMapView.this);
                    }
                });
            }
        });
        prepareThread.start();
    }

    /**
     * draw map on canvas
     * @param canvas target canvas
     */
    private void drawMap(Canvas canvas){
        float ratio = (float)canvas.getWidth() / SVGParser.xMax;

        for(CountrySection countrySection : countrySections){
            List<List<Float>> xPathList = countrySection.getXPathList();
            List<List<Float>> yPathList = countrySection.getYPathList();
            int numList = xPathList.size();
            for (int i = 0; i < numList; ++i) {
                Path path = new Path();
                path.moveTo(xPathList.get(i).get(0) * ratio, yPathList.get(i).get(0) * ratio);
                int numPoint = xPathList.get(i).size();
                for (int j = 1; j < numPoint; ++j) {
                    path.lineTo(xPathList.get(i).get(j) * ratio, yPathList.get(i).get(j) * ratio);
                }
                Paint paint = countryPaints.get(countrySection.getCountryCode());
                if(paint != null){
                    canvas.drawPath(path, paint);
                }
                canvas.drawPath(path, defaultPaint);
            }
        }
    }

    /**
     * set filling color
     * @param countryCode target country code
     * @param color filling color
     */
    public void setCountryColor(String countryCode, String color){
        Paint paint = new Paint();
        paint.setColor(Color.parseColor(color));
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        countryPaints.put(countryCode, paint);
    }

    /**
     * set filling color
     * @param countryCode target country code
     * @param red 0 to 255
     * @param green 0 to 255
     * @param blue 0 to 255
     */
    public void setCountryColor(String countryCode, int red, int green, int blue){
        Paint paint = new Paint();
        paint.setColor(Color.rgb(red, green, blue));
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        countryPaints.put(countryCode, paint);
    }

    /**
     * remove filling color
     * @param countryCode target country code
     */
    public void removeCountryColor(String countryCode){
        countryPaints.remove(countryCode);
    }

    /**
     * clear all filling color
     */
    public void clearCountryColor(){
        countryPaints = new HashMap<>();
    }

    /**
     * refresh GeoMapView
     * you need call this method after initialized
     */
    public void refresh(){
        final Handler handler = new Handler();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = Bitmap.createBitmap(GeoMapView.this.getWidth(),
                        GeoMapView.this.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bitmap);
                drawMap(canvas);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        GeoMapView.this.setImageBitmap(bitmap);
                    }
                });
            }
        });
        thread.start();
    }

    /**
     * stop all threads
     */
    public void destroy(){
        if(prepareThread != null) {
            prepareThread.interrupt();
            prepareThread = null;
        }
        if(thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    /**
     * set OnInitializedListener
     * @param listener
     */
    public void setOnInitializedListener(OnInitializedListener listener){
        this.listener = listener;
    }
}
