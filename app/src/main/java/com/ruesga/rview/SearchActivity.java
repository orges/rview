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
package com.ruesga.rview;

import android.animation.Animator;
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.res.TypedArray;
import android.databinding.DataBindingUtil;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.support.annotation.DrawableRes;
import android.support.annotation.Nullable;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.util.Pair;
import android.support.v4.view.ViewCompat;
import android.support.v7.widget.ListPopupWindow;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.animation.AccelerateInterpolator;

import com.arlib.floatingsearchview.FloatingSearchView;
import com.arlib.floatingsearchview.suggestions.model.SearchSuggestion;
import com.ruesga.rview.adapters.SimpleDropDownAdapter;
import com.ruesga.rview.annotations.ProguardIgnored;
import com.ruesga.rview.databinding.SearchActivityBinding;
import com.ruesga.rview.gerrit.GerritApi;
import com.ruesga.rview.gerrit.filter.ChangeQuery;
import com.ruesga.rview.gerrit.filter.Option;
import com.ruesga.rview.gerrit.filter.antlr.QueryParseException;
import com.ruesga.rview.gerrit.model.AccountInfo;
import com.ruesga.rview.gerrit.model.ProjectInfo;
import com.ruesga.rview.gerrit.model.ProjectType;
import com.ruesga.rview.misc.ActivityHelper;
import com.ruesga.rview.misc.AndroidHelper;
import com.ruesga.rview.misc.ModelHelper;
import com.ruesga.rview.misc.StringHelper;
import com.ruesga.rview.preferences.Constants;
import com.ruesga.rview.preferences.Preferences;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import me.tatarka.rxloader2.RxLoader1;
import me.tatarka.rxloader2.RxLoaderManager;
import me.tatarka.rxloader2.RxLoaderManagerCompat;
import me.tatarka.rxloader2.RxLoaderObserver;
import me.tatarka.rxloader2.safe.SafeObservable;

public class SearchActivity extends AppCompatDelegateActivity {

    private static final int MAX_SUGGESTIONS = 5;

    private static final int FETCH_SUGGESTIONS_MESSAGE = 1;

    private static final String EXTRA_REVEALED = "revealed";

    @SuppressWarnings({"UnusedParameters", "unused"})
    @ProguardIgnored
    public static class EventHandlers {
        private SearchActivity mActivity;

        public EventHandlers(SearchActivity activity) {
            mActivity = activity;
        }

        public void onDismissByOutsideTouch(View v) {
            mActivity.exitReveal();
        }
    }

    private static class Suggestion implements SearchSuggestion {

        private final String mFilter;
        private final String mPartial;
        private final String mSuggestionText;
        private final int mSuggestionIcon;

        Suggestion(String filter, String suggestion, @DrawableRes int icon) {
            this(filter, "", suggestion, icon);
        }

        Suggestion(String filter, String partial, String suggestion, @DrawableRes int icon) {
            mFilter = filter;
            mPartial = partial;
            mSuggestionText = suggestion;
            mSuggestionIcon = icon;
        }

        Suggestion(Parcel in) {
            mFilter = in.readString();
            mPartial = in.readString();
            mSuggestionText = in.readString();
            mSuggestionIcon = in.readInt();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
            parcel.writeString(mFilter);
            parcel.writeString(mPartial);
            parcel.writeString(mSuggestionText);
            parcel.writeInt(mSuggestionIcon);
        }

        @Override
        public String getBody() {
            return mPartial + mSuggestionText;
        }

        public static final Creator<Suggestion> CREATOR = new Creator<Suggestion>() {
            @Override
            public Suggestion createFromParcel(Parcel in) {
                return new Suggestion(in);
            }

            @Override
            public Suggestion[] newArray(int size) {
                return new Suggestion[size];
            }
        };
    }

    private final RxLoaderObserver<Pair<String, List<AccountInfo>>> mAccountSuggestionsObserver
            = new RxLoaderObserver<Pair<String, List<AccountInfo>>>() {
        @Override
        public void onNext(Pair<String, List<AccountInfo>> response) {
            if (mBinding.searchView != null) {
                List<Suggestion> suggestions = new ArrayList<>(response.second.size());
                for (AccountInfo account : response.second) {
                    String suggestion = getString(
                            R.string.account_suggest_format, account.name, account.email);
                    suggestions.add(new Suggestion(
                            response.first, suggestion, R.drawable.ic_search));
                }
                mBinding.searchView.swapSuggestions(suggestions);
            }
        }
    };

    @SuppressWarnings("Convert2streamapi")
    private final RxLoaderObserver<Pair<String, Map<String, ProjectInfo>>> mProjectSuggestionsObserver
            = new RxLoaderObserver<Pair<String, Map<String, ProjectInfo>>>() {
        @Override
        public void onNext(Pair<String, Map<String, ProjectInfo>> result) {
            if (mBinding.searchView != null) {
                List<Suggestion> suggestions = new ArrayList<>(result.second.size());
                for (ProjectInfo project : result.second.values()) {
                    try {
                        suggestions.add(new Suggestion(
                                result.first,
                                URLDecoder.decode(project.id, "UTF-8"),
                                R.drawable.ic_search));
                    } catch (UnsupportedEncodingException ex) {
                        // Ignore
                    }
                }
                mBinding.searchView.swapSuggestions(suggestions);
            }
        }
    };

    private Handler.Callback mMessenger = message -> {
        if (message.what == FETCH_SUGGESTIONS_MESSAGE) {
            performFilter((String) message.obj);
        }
        return false;
    };

    private Handler mHandler;

    private SearchActivityBinding mBinding;
    private int mCurrentOption;
    private int[] mIcons;
    private Drawable mSuggestionIcon;

    private RxLoader1<String, Pair<String, List<AccountInfo>>> mAccountSuggestionsLoader;
    private RxLoader1<String, Pair<String, Map<String, ProjectInfo>>> mProjectSuggestionsLoader;

    private List<String> mSuggestions;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mHandler = new Handler(mMessenger);
        mSuggestionIcon = ContextCompat.getDrawable(this, R.drawable.ic_search);
        DrawableCompat.setTint(mSuggestionIcon, ContextCompat.getColor(
                this, R.color.gray_active_icon));

        fillSuggestions();

        mBinding = DataBindingUtil.setContentView(this, R.layout.search_activity);
        mBinding.setHandlers(new EventHandlers(this));

        mIcons = loadSearchIcons();

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.menu_search);
            getSupportActionBar().setDisplayHomeAsUpEnabled(false);
            getSupportActionBar().setDefaultDisplayHomeAsUpEnabled(false);
        }

        // Configure the suggestions loaders
        RxLoaderManager loaderManager = RxLoaderManagerCompat.get(this);
        mAccountSuggestionsLoader = loaderManager.create(
                "accounts", this::fetchAccountSuggestions, mAccountSuggestionsObserver);
        mProjectSuggestionsLoader = loaderManager.create(
                "projects", this::fetchProjectSuggestions, mProjectSuggestionsObserver);

        // Configure the search view
        mBinding.searchView.setOnSearchListener(new FloatingSearchView.OnSearchListener() {
            @Override
            public boolean onSuggestionClicked(SearchSuggestion suggestion) {
                if (mCurrentOption == Constants.SEARCH_MODE_CUSTOM) {
                    return true;
                }

                // Directly complete the search
                final Suggestion s = (Suggestion) suggestion;
                performSearch(s.mSuggestionText);
                return false;
            }

            @Override
            public void onSearchAction(String currentQuery) {
                performSearch(currentQuery);
            }
        });
        mBinding.searchView.setOnQueryChangeListener((oldFilter, newFilter) -> {
            mHandler.removeMessages(FETCH_SUGGESTIONS_MESSAGE);
            final Message msg = Message.obtain(mHandler, FETCH_SUGGESTIONS_MESSAGE, newFilter);
            msg.arg1 = mCurrentOption;
            mHandler.sendMessageDelayed(msg, 500L);
        });
        mBinding.searchView.setOnBindSuggestionCallback(
                (v, imageView, textView, suggestion, position) -> {
            final Suggestion s = (Suggestion) suggestion;
            textView.setText(performFilterHighlight(s));
            if (s.mSuggestionIcon != 0) {
                Drawable dw = ContextCompat.getDrawable(this, s.mSuggestionIcon);
                DrawableCompat.setTint(mSuggestionIcon, ContextCompat.getColor(
                        this, R.color.gray_active_icon));
                imageView.setImageDrawable(dw);
            } else {
                imageView.setImageDrawable(null);
            }
        });
        mBinding.searchView.setOnMenuItemClickListener(item -> performShowOptions());
        clearSuggestions();

        mCurrentOption = Preferences.getAccountSearchMode(this, Preferences.getAccount(this));
        mBinding.searchView.setCustomIcon(ContextCompat.getDrawable(this, mIcons[mCurrentOption]));

        configureSearchHint();

        boolean revealed = false;
        if (savedInstanceState != null) {
            revealed = savedInstanceState.getBoolean(EXTRA_REVEALED, false);
        }
        if (!revealed) {
            enterReveal();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(EXTRA_REVEALED, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ActivityHelper.LIST_RESULT_CODE && resultCode == RESULT_OK) {
            // Directly finish this activity. The search data was used
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        exitReveal();
    }

    @Override
    public void onPause() {
        super.onPause();

        if (AndroidHelper.isLollipopOrGreater()) {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    public boolean onKeyDown(int keycode, KeyEvent e) {
        switch(keycode) {
            case KeyEvent.KEYCODE_MENU:
                mBinding.searchView.openMenu(true);
                return true;
        }

        return super.onKeyDown(keycode, e);
    }

    private void configureSearchHint() {
        switch (mCurrentOption) {
            case Constants.SEARCH_MODE_CHANGE:
                mBinding.searchView.setSearchHint(getString(R.string.search_by_change_hint));
                break;
            case Constants.SEARCH_MODE_COMMIT:
                mBinding.searchView.setSearchHint(getString(R.string.search_by_commit_hint));
                break;
            case Constants.SEARCH_MODE_PROJECT:
                mBinding.searchView.setSearchHint(getString(R.string.search_by_project_hint));
                break;
            case Constants.SEARCH_MODE_USER:
                mBinding.searchView.setSearchHint(getString(R.string.search_by_user_hint));
                break;
            case Constants.SEARCH_MODE_COMMIT_MESSAGE:
                mBinding.searchView.setSearchHint(getString(R.string.search_by_commit_message_hint));
                break;
            case Constants.SEARCH_MODE_CUSTOM:
                mBinding.searchView.setSearchHint(getString(R.string.search_by_custom_hint));
                break;
        }

        mBinding.searchView.setSearchText(null);
    }

    private void performShowOptions() {
        final ListPopupWindow popupWindow = new ListPopupWindow(this);
        ArrayList<String> values = new ArrayList<>(
                Arrays.asList(getResources().getStringArray(R.array.search_options_labels)));
        String value = values.get(mCurrentOption);
        SimpleDropDownAdapter adapter = new SimpleDropDownAdapter(this, values, mIcons, value);
        popupWindow.setAnchorView(mBinding.anchor);
        popupWindow.setDropDownGravity(Gravity.END);
        popupWindow.setAdapter(adapter);
        popupWindow.setContentWidth(adapter.measureContentWidth());
        popupWindow.setOnItemClickListener((parent, view, position, id) -> {
            popupWindow.dismiss();
            mCurrentOption = position;
            Preferences.setAccountSearchMode(this, Preferences.getAccount(this), mCurrentOption);
            configureSearchHint();
            mBinding.searchView.setCustomIcon(ContextCompat.getDrawable(this, mIcons[position]));
            clearSuggestions();
        });
        popupWindow.setModal(true);
        popupWindow.show();
    }

    private void performFilter(String filter) {
        if (TextUtils.isEmpty(filter)) {

            return;
        }

        switch (mCurrentOption) {
            case Constants.SEARCH_MODE_CHANGE:
            case Constants.SEARCH_MODE_COMMIT:
            case Constants.SEARCH_MODE_COMMIT_MESSAGE:
                // We cannot show suggestion on this modes
                break;
            case Constants.SEARCH_MODE_PROJECT:
                requestProjectSuggestions(filter);
                break;
            case Constants.SEARCH_MODE_USER:
                requestAccountSuggestions(filter);
                break;
            case Constants.SEARCH_MODE_CUSTOM:
                requestCustomSuggestions(filter);
                break;
        }
    }

    private void clearSuggestions() {
        mBinding.searchView.swapSuggestions(new ArrayList<>());
    }

    private void performSearch(String query) {
        if (TextUtils.isEmpty(query)) {
            clearSuggestions();
            return;
        }

        ChangeQuery filter = null;
        switch (mCurrentOption) {
            case Constants.SEARCH_MODE_CHANGE:
                boolean isLegacyChangeNumber;
                try {
                    int i = Integer.parseInt(query);
                    isLegacyChangeNumber = i > 0;
                } catch (NumberFormatException ex) {
                    isLegacyChangeNumber = false;
                }

                if (isLegacyChangeNumber || StringHelper.GERRIT_CHANGE.matcher(query).matches()) {
                    filter = new ChangeQuery().change(String.valueOf(query));
                } else {
                    // Not a valid filter
                    AndroidHelper.showErrorSnackbar(
                            this, mBinding.getRoot(), R.string.search_not_a_valid_change);
                    return;
                }

                break;
            case Constants.SEARCH_MODE_COMMIT:
                if (StringHelper.GERRIT_COMMIT.matcher(query).matches()) {
                    filter = new ChangeQuery().commit(String.valueOf(query));
                } else {
                    // Not a valid filter
                    AndroidHelper.showErrorSnackbar(
                            this, mBinding.getRoot(), R.string.search_not_a_valid_commit);
                    return;
                }
                break;
            case Constants.SEARCH_MODE_PROJECT:
                filter = new ChangeQuery().project(String.valueOf(query));
                break;
            case Constants.SEARCH_MODE_USER:
                filter = new ChangeQuery().owner(String.valueOf(query));
                break;
            case Constants.SEARCH_MODE_COMMIT_MESSAGE:
                filter = new ChangeQuery().message(String.valueOf(query));
                break;
            case Constants.SEARCH_MODE_CUSTOM:
                try {
                    filter = ChangeQuery.parse(query);
                } catch (QueryParseException ex) {
                    // Not a valid filter
                    AndroidHelper.showErrorSnackbar(
                            this, mBinding.getRoot(), R.string.search_not_a_valid_custom_query);
                    return;
                }
                break;
        }

        // Open the activity
        ActivityHelper.openChangeListByFilterActivity(this, null, filter, true);
    }

    private int[] loadSearchIcons() {
        TypedArray ta = getResources().obtainTypedArray(R.array.search_options_icons);
        int count = ta.length();
        int[] icons = new int[count];
        for (int i = 0; i < count; i++) {
            icons[i] = ta.getResourceId(i, -1);
        }
        ta.recycle();
        return icons;
    }

    @SuppressWarnings("ConstantConditions")
    private Observable<Pair<String, List<AccountInfo>>> fetchAccountSuggestions(String filter) {
        final GerritApi api = ModelHelper.getGerritApi(this);
        return SafeObservable.fromNullCallable(() -> {
                    List<AccountInfo> accounts =
                            api.getAccountsSuggestions(
                                    filter, MAX_SUGGESTIONS, Option.INSTANCE).blockingFirst();
                    return new Pair<>(filter, accounts);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void requestAccountSuggestions(String filter) {
        mAccountSuggestionsLoader.clear();
        mAccountSuggestionsLoader.restart(filter);
    }

    @SuppressWarnings("Convert2streamapi")
    private void requestCustomSuggestions(String filter) {
        // Do no perform suggestion when there are selection or cursor is not at the end
        // of the textview
        if (mBinding.searchView.getSelectionStart() != mBinding.searchView.getSelectionStart()
                || mBinding.searchView.getSelectionStart() < mBinding.searchView.getText().length()) {
            clearSuggestions();
            return;
        }

        // Extract the current filter
        int pos = filter.lastIndexOf(" ");
        pos = pos == -1 ? 0 : ++pos;
        String currentFilter = filter.substring(pos);
        String partial = filter.substring(0, pos);

        // Some sanitize checks
        if (TextUtils.isEmpty(currentFilter)) {
            clearSuggestions();
            return;
        }
        char c = filter.charAt(pos);
        if (!Character.isLetter(c) && !Character.isDigit(c)) {
            clearSuggestions();
            return;
        }

        final List<Suggestion> suggestions = new ArrayList<>();
        for (String s : mSuggestions) {
            if (s.startsWith(currentFilter)) {
                suggestions.add(new Suggestion(currentFilter, partial, s, R.drawable.ic_search));
            }
        }
        mBinding.searchView.swapSuggestions(suggestions);
    }

    @SuppressWarnings("ConstantConditions")
    private Observable<Pair<String, Map<String, ProjectInfo>>> fetchProjectSuggestions(String filter) {
        final GerritApi api = ModelHelper.getGerritApi(this);
        return SafeObservable.fromNullCallable(() -> {
                    Map<String, ProjectInfo> projects =
                            api.getProjects(MAX_SUGGESTIONS, null, null, null, filter,
                                null, null, null, ProjectType.ALL, null).blockingFirst();
                    return new Pair<>(filter, projects);
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread());
    }

    private void requestProjectSuggestions(String filter) {
        mProjectSuggestionsLoader.clear();
        mProjectSuggestionsLoader.restart(filter);
    }

    private CharSequence performFilterHighlight(Suggestion suggestion) {
        Spannable span = Spannable.Factory.getInstance().newSpannable(suggestion.mSuggestionText);
        int pos = 0;
        final String suggestionNoCase = suggestion.mSuggestionText.toLowerCase();
        final String filterNoCase = suggestion.mFilter.toLowerCase();
        while ((pos = suggestionNoCase.indexOf(filterNoCase, pos)) != -1) {
            final int length = suggestion.mFilter.length();
            final StyleSpan bold = new StyleSpan(android.graphics.Typeface.BOLD);
            final ForegroundColorSpan color = new ForegroundColorSpan(
                    ContextCompat.getColor(this, R.color.accent));
            span.setSpan(bold, pos, pos + length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(color, pos, pos + length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            pos += length;
            if (pos >= suggestionNoCase.length()) {
                break;
            }
        }
        return span;
    }

    private void fillSuggestions() {
        mSuggestions = new ArrayList<>();
        int count = ChangeQuery.FIELDS_NAMES.length;
        for (int i = 0; i < count; i++) {
            mSuggestions.add(ChangeQuery.FIELDS_NAMES[i] + ":");
            if (ChangeQuery.SUGGEST_TYPES[i] != null && ChangeQuery.SUGGEST_TYPES[i].isEnum()) {
                for (Object o : ChangeQuery.SUGGEST_TYPES[i].getEnumConstants()) {
                    String val = String.valueOf(o).toLowerCase();
                    mSuggestions.add(ChangeQuery.FIELDS_NAMES[i] + ":" + val + " ");
                }
            }
        }

        Collections.sort(mSuggestions);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void enterReveal() {
        if (AndroidHelper.isLollipopOrGreater()) {
            final View target = mBinding.searchView;
            final View bounds = mBinding.toolbar;
            ViewCompat.postOnAnimation(bounds, () -> {
                int cx = bounds.getMeasuredWidth();
                int cy = bounds.getMeasuredHeight() / 2;
                Animator anim = ViewAnimationUtils.createCircularReveal(target, cx, cy, 0, cx);
                anim.setInterpolator(new AccelerateInterpolator());
                anim.setDuration(350L);
                anim.addListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mBinding.toolbar.setVisibility(View.VISIBLE);
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                    }

                    @Override
                    public void onAnimationRepeat(Animator animation) {
                    }
                });
                anim.start();
            });
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void exitReveal() {
        if (!AndroidHelper.isLollipopOrGreater()) {
            finish();
            return;
        }

        final View target = mBinding.searchView;
        final View bounds = mBinding.toolbar;
        ViewCompat.postOnAnimation(bounds, () -> {
            int cx = bounds.getMeasuredWidth();
            int cy = bounds.getMeasuredHeight() / 2;
            Animator anim = ViewAnimationUtils.createCircularReveal(target, cx, cy, cx, 0);
            anim.setInterpolator(new AccelerateInterpolator());
            anim.setDuration(250L);
            anim.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {
                    mBinding.toolbar.setVisibility(View.GONE);
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    mBinding.searchView.setVisibility(View.INVISIBLE);
                    finish();
                }

                @Override
                public void onAnimationCancel(Animator animation) {
                }

                @Override
                public void onAnimationRepeat(Animator animation) {
                }
            });
            anim.start();
        });
    }
}
