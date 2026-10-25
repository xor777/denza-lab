package dev.denza.adaslink.stocknative;

/** Mutable sender state; host-neutral and not an input checkpoint. */
public class RegionalWeatherParam {
    private int cityCode;
    private int countryCode = 1;
    private int provinceCode;
    private int regionId;
    private int weatherType;

    public int getCityCode() {
        return this.cityCode;
    }

    public int getCountryCode() {
        return this.countryCode;
    }

    public int getProvinceCode() {
        return this.provinceCode;
    }

    public int getRegionId() {
        return this.regionId;
    }

    public int getWeatherType() {
        return this.weatherType;
    }

    public void setCityCode(int i2) {
        this.cityCode = i2;
    }

    public void setProvinceCode(int i2) {
        this.provinceCode = i2;
    }

    public void setRegionId(int i2) {
        this.regionId = i2;
    }

    public void setRegionParam(int i2, int i3, int i4, int i5) {
        this.regionId = i2;
        this.cityCode = i4;
        this.provinceCode = i3;
        this.countryCode = i5;
    }

    public void setWeatherType(int i2) {
        this.weatherType = i2;
    }
}
