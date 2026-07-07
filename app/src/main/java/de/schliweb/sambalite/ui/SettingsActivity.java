/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import com.google.android.material.materialswitch.MaterialSwitch;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.security.BiometricAuthHelper;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.PreferencesManager;
import javax.inject.Inject;

/**
 * Settings screen: security options (previously a dialog) and thumbnail preferences. When any
 * authentication requirement is enabled, the screen itself is gated behind device authentication so
 * security protections cannot be disabled by an unauthorized user.
 */
public class SettingsActivity extends AppCompatActivity {

  /** Size options (in MB) offered for the thumbnail size limit; 0 means no limit. */
  private static final int[] THUMBNAIL_SIZE_OPTIONS_MB = {1, 2, 5, 10, 25, 0};

  @Inject PreferencesManager preferencesManager;

  /** Creates an intent to start this activity. */
  public static @NonNull Intent createIntent(@NonNull Context context) {
    return new Intent(context, SettingsActivity.class);
  }

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    ((SambaLiteApp) getApplication()).getAppComponent().inject(this);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_settings);

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
      getSupportActionBar().setTitle(R.string.settings);
    }

    View content = findViewById(R.id.settings_content);

    // If any auth requirement is on, require authentication before showing the settings,
    // matching the protection the old security dialog had.
    boolean anyAuthEnabled =
        preferencesManager.isAuthRequiredForAccess()
            || preferencesManager.isAuthRequiredForPasswordReveal();
    if (anyAuthEnabled && BiometricAuthHelper.isDeviceAuthAvailable(this)) {
      content.setVisibility(View.INVISIBLE);
      BiometricAuthHelper.authenticate(
          this,
          getString(R.string.auth_title_access),
          getString(R.string.auth_subtitle_security_settings),
          new BiometricAuthHelper.AuthCallback() {
            @Override
            public void onAuthSuccess() {
              content.setVisibility(View.VISIBLE);
              bindSettings();
            }

            @Override
            public void onAuthFailure(String errorMessage) {
              LogUtils.w("SettingsActivity", "Authentication failed: " + errorMessage);
              finish();
            }

            @Override
            public void onAuthCancelled() {
              LogUtils.d("SettingsActivity", "Authentication cancelled");
              finish();
            }
          });
    } else {
      bindSettings();
    }
  }

  @Override
  public boolean onSupportNavigateUp() {
    finish();
    return true;
  }

  /** Populates all settings rows and wires instant-apply listeners. */
  private void bindSettings() {
    boolean deviceAuthAvailable = BiometricAuthHelper.isDeviceAuthAvailable(this);

    MaterialSwitch authAccess = findViewById(R.id.auth_access_switch);
    MaterialSwitch authReveal = findViewById(R.id.auth_password_reveal_switch);
    TextView authUnavailable = findViewById(R.id.auth_not_available_text);

    authAccess.setChecked(preferencesManager.isAuthRequiredForAccess());
    authReveal.setChecked(preferencesManager.isAuthRequiredForPasswordReveal());
    if (!deviceAuthAvailable) {
      authAccess.setEnabled(false);
      authReveal.setEnabled(false);
      authUnavailable.setVisibility(View.VISIBLE);
    }
    authAccess.setOnCheckedChangeListener(
        (button, checked) -> preferencesManager.saveAuthRequiredForAccess(checked));
    authReveal.setOnCheckedChangeListener(
        (button, checked) -> preferencesManager.saveAuthRequiredForPasswordReveal(checked));

    MaterialSwitch showThumbnails = findViewById(R.id.show_thumbnails_switch);
    showThumbnails.setChecked(preferencesManager.getShowThumbnails());
    showThumbnails.setOnCheckedChangeListener(
        (button, checked) -> preferencesManager.saveShowThumbnails(checked));

    MaterialSwitch lazyLoad = findViewById(R.id.thumbnail_lazy_switch);
    lazyLoad.setChecked(preferencesManager.isThumbnailLazyLoadOnTap());
    lazyLoad.setOnCheckedChangeListener(
        (button, checked) -> preferencesManager.saveThumbnailLazyLoadOnTap(checked));

    AutoCompleteTextView sizeDropdown = findViewById(R.id.thumbnail_size_dropdown);
    String[] labels = new String[THUMBNAIL_SIZE_OPTIONS_MB.length];
    int currentIndex = 2; // default: 5 MB
    int currentValue = preferencesManager.getThumbnailMaxSizeMb();
    for (int i = 0; i < THUMBNAIL_SIZE_OPTIONS_MB.length; i++) {
      int value = THUMBNAIL_SIZE_OPTIONS_MB[i];
      labels[i] =
          value == 0
              ? getString(R.string.thumbnail_size_no_limit)
              : getString(R.string.thumbnail_size_mb, value);
      if (value == currentValue) {
        currentIndex = i;
      }
    }
    sizeDropdown.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
    sizeDropdown.setText(labels[currentIndex], false);
    sizeDropdown.setOnItemClickListener(
        (parent, view, position, id) ->
            preferencesManager.saveThumbnailMaxSizeMb(THUMBNAIL_SIZE_OPTIONS_MB[position]));
  }
}
