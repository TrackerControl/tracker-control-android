package jp.gr.java_conf.androtaku.geomap;

import java.util.List;

/**
 * Created by takuma on 2015/07/18.
 */
public class CountrySection {
    private String countryCode;
    private List<List<Float>> xPathList;
    private List<List<Float>> yPathList;

    public void setCountryCode(String countryCode){
        this.countryCode = countryCode;
    }
    public void setXPathList(List<List<Float>> xPathList){
        this.xPathList = xPathList;
    }
    public void setYPathList(List<List<Float>> yPathList){
        this.yPathList = yPathList;
    }

    public String getCountryCode(){
        return this.countryCode;
    }
    public List<List<Float>> getXPathList(){
        return this.xPathList;
    }
    public List<List<Float>> getYPathList(){
        return this.yPathList;
    }
}
