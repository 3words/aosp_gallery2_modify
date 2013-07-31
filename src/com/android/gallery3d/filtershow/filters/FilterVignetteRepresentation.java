/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.gallery3d.filtershow.filters;

import com.android.gallery3d.R;
import com.android.gallery3d.filtershow.editors.EditorVignette;
import com.android.gallery3d.filtershow.imageshow.Oval;

public class FilterVignetteRepresentation extends FilterBasicRepresentation implements Oval {
    private static final String LOGTAG = "FilterVignetteRepresentation";
    private float mCenterX = Float.NaN;
    private float mCenterY;
    private float mRadiusX = Float.NaN;
    private float mRadiusY;

    public FilterVignetteRepresentation() {
        super("Vignette", -100, 50, 100);
        setSerializationName("VIGNETTE");
        setShowParameterValue(true);
        setFilterType(FilterRepresentation.TYPE_VIGNETTE);
        setTextId(R.string.vignette);
        setEditorId(EditorVignette.ID);
        setName("Vignette");
        setFilterClass(ImageFilterVignette.class);
        setMinimum(-100);
        setMaximum(100);
        setDefaultValue(0);
    }

    @Override
    public void useParametersFrom(FilterRepresentation a) {
        super.useParametersFrom(a);
        mCenterX = ((FilterVignetteRepresentation) a).mCenterX;
        mCenterY = ((FilterVignetteRepresentation) a).mCenterY;
        mRadiusX = ((FilterVignetteRepresentation) a).mRadiusX;
        mRadiusY = ((FilterVignetteRepresentation) a).mRadiusY;
    }

    @Override
    public FilterRepresentation copy() {
        FilterVignetteRepresentation representation = new FilterVignetteRepresentation();
        copyAllParameters(representation);
        return representation;
    }

    @Override
    protected void copyAllParameters(FilterRepresentation representation) {
        super.copyAllParameters(representation);
        representation.useParametersFrom(this);
    }

    @Override
    public void setCenter(float centerX, float centerY) {
        mCenterX = centerX;
        mCenterY = centerY;
    }

    @Override
    public float getCenterX() {
        return mCenterX;
    }

    @Override
    public float getCenterY() {
        return mCenterY;
    }

    @Override
    public void setRadius(float radiusX, float radiusY) {
        mRadiusX = radiusX;
        mRadiusY = radiusY;
    }

    @Override
    public void setRadiusX(float radiusX) {
        mRadiusX = radiusX;
    }

    @Override
    public void setRadiusY(float radiusY) {
        mRadiusY = radiusY;
    }

    @Override
    public float getRadiusX() {
        return mRadiusX;
    }

    @Override
    public float getRadiusY() {
        return mRadiusY;
    }

    public boolean isCenterSet() {
        return mCenterX != Float.NaN;
    }

    @Override
    public boolean isNil() {
        return getValue() == 0;
    }

    @Override
    public boolean equals(FilterRepresentation representation) {
        if (!super.equals(representation)) {
            return false;
        }
        if (representation instanceof FilterVignetteRepresentation) {
            FilterVignetteRepresentation rep = (FilterVignetteRepresentation) representation;
            if (rep.getCenterX() == getCenterX()
                    && rep.getCenterY() == getCenterY()
                    && rep.getRadiusX() == getRadiusX()
                    && rep.getRadiusY() == getRadiusY()) {
                return true;
            }
        }
        return false;
    }

    private static final String[] sParams = {
            "Name", "value", "mCenterX", "mCenterY", "mRadiusX",
            "mRadiusY"
    };

    @Override
    public String[][] serializeRepresentation() {
        String[][] ret = {
                { sParams[0], getName() },
                { sParams[1], Integer.toString(getValue()) },
                { sParams[2], Float.toString(mCenterX) },
                { sParams[3], Float.toString(mCenterY) },
                { sParams[4], Float.toString(mRadiusX) },
                { sParams[5], Float.toString(mRadiusY) }
        };
        return ret;
    }

    @Override
    public void deSerializeRepresentation(String[][] rep) {
        super.deSerializeRepresentation(rep);
        for (int i = 0; i < rep.length; i++) {
            String key = rep[i][0];
            String value = rep[i][1];
            if (sParams[0].equals(key)) {
                setName(value);
            } else if (sParams[1].equals(key)) {
               setValue(Integer.parseInt(value));
            } else if (sParams[2].equals(key)) {
                mCenterX = Float.parseFloat(value);
            } else if (sParams[3].equals(key)) {
                mCenterY = Float.parseFloat(value);
            } else if (sParams[4].equals(key)) {
                mRadiusX = Float.parseFloat(value);
            } else if (sParams[5].equals(key)) {
                mRadiusY = Float.parseFloat(value);
            }
        }
    }
}
