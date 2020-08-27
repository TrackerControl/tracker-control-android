package jp.gr.java_conf.androtaku.geomap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.util.AttributeSet;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.List;

import static android.graphics.Bitmap.Config.ARGB_8888;

/**
 * Created by takuma on 2015/07/18.
 * Updated by Konrad Kollnig on 17 August 2020
 */
public class GeoMapView extends androidx.appcompat.widget.AppCompatImageView {
    private String TAG = GeoMapView.class.getSimpleName();
    private List<CountrySection> _countries;
    private Context _context;
    private Paint _paint;
    private HashMap<String, Paint> _countryColours = new HashMap<>();
    private OnShownListener listener;

    public GeoMapView(Context context) {
        super(context);
        this._context = context;
    }

    public GeoMapView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this._context = context;
    }

    public void show() {
        _paint = new Paint();
        _paint.setColor(ContextCompat.getColor(_context, R.color.countryStroke));
        _paint.setStyle(Paint.Style.STROKE);
        _paint.setAntiAlias(true);

        final Handler handler = new Handler();
        new Thread(() -> {
            _countries = SVGParser.getCountries(_context);

            int width = getWidth();
            int height = getHeight();

            if (width == 0 || height == 0) {
                Log.e(TAG, "Loading failed. Width or height equals zero.");
                handler.post(() -> shown(false));
                return;
            }

            final Bitmap bitmap = Bitmap.createBitmap(
                    width,
                    height,
                    ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawMap(canvas);

            // run on UI
            handler.post(() -> {
                GeoMapView.this.setImageBitmap(bitmap);
                shown(true);
            });
        }).start();
    }

    private void shown(boolean success) {
        if (listener != null)
            listener.onShown(success);
    }

    /**
     * draw map on canvas
     *
     * @param canvas target canvas
     */
    private void drawMap(Canvas canvas) {
        float ratio = (float) canvas.getWidth() / SVGParser.xMax;

        for (CountrySection countrySection : _countries) {
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
                Paint paint = _countryColours.get(countrySection.getCountryCode());
                if (paint != null) {
                    canvas.drawPath(path, paint);
                }
                canvas.drawPath(path, this._paint);
            }
        }
    }

    /**
     * set filling color
     *
     * @param countryCode target country code
     * @param color       filling color
     */
    public void highlightCountry(String countryCode, String color) {
        Paint paint = new Paint();
        paint.setColor(Color.parseColor(color));
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        _countryColours.put(countryCode, paint);
    }

    public void setOnShownListener(OnShownListener listener){
        this.listener = listener;
    }
}
