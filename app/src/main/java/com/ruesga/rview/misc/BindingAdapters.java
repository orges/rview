/*
 * Copyright (C) 2016 Jorge Ruesga
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
package com.ruesga.rview.misc;

import android.databinding.BindingAdapter;
import android.support.design.widget.AppBarLayout;
import android.support.v7.widget.Toolbar;

import com.ruesga.rview.annotations.ProguardIgnored;

@ProguardIgnored
@SuppressWarnings("unused")
public class BindingAdapters {

    @BindingAdapter("toolbarScrollFlags")
    public static void toolbarScrollFlags(Toolbar toolbar, boolean hasTabs) {
        AppBarLayout.LayoutParams params = ((AppBarLayout.LayoutParams) toolbar.getLayoutParams());
        params.setScrollFlags(hasTabs ? AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                | AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS : 0);
    }
}