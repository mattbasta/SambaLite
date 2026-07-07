/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.util;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import de.schliweb.sambalite.ui.FileSortOption;

/**
 * Manager for handling application preferences. This class provides methods for storing and
 * retrieving UI preferences, such as sorting options.
 */
public class PreferencesManager {
  private static final String PREFS_NAME = "de.schliweb.sambalite.preferences";
  private static final String KEY_SORT_OPTION = "sort_option";
  private static final String KEY_DIRECTORIES_FIRST = "directories_first";
  private static final String KEY_SHOW_HIDDEN_FILES = "show_hidden_files";
  private static final String KEY_AUTH_REQUIRED_FOR_ACCESS = "auth_required_for_access";
  private static final String KEY_AUTH_REQUIRED_FOR_PASSWORD_REVEAL =
      "auth_required_for_password_reveal";
  private static final String KEY_SHOW_THUMBNAILS = "show_thumbnails";
  private static final String KEY_THUMBNAIL_MAX_SIZE_MB = "thumbnail_max_size_mb";
  private static final String KEY_THUMBNAIL_LAZY_LOAD_ON_TAP = "thumbnail_lazy_load_on_tap";

  /** Default maximum file size (in MB) for which thumbnails are generated. */
  public static final int DEFAULT_THUMBNAIL_MAX_SIZE_MB = 5;

  private final SharedPreferences preferences;

  /**
   * Creates a new PreferencesManager.
   *
   * @param context The application context
   */
  public PreferencesManager(@NonNull Context context) {
    this.preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    LogUtils.d("PreferencesManager", "PreferencesManager initialized");
  }

  /**
   * Saves the sort option.
   *
   * @param sortOption The sort option to save
   */
  public void saveSortOption(@NonNull FileSortOption sortOption) {
    LogUtils.d("PreferencesManager", "Saving sort option: " + sortOption);
    preferences.edit().putString(KEY_SORT_OPTION, sortOption.name()).apply();
  }

  /**
   * Gets the saved sort option.
   *
   * @return The saved sort option, or NAME if none is saved
   */
  public @NonNull FileSortOption getSortOption() {
    String sortOptionName = preferences.getString(KEY_SORT_OPTION, FileSortOption.NAME.name());
    try {
      FileSortOption option = FileSortOption.valueOf(sortOptionName);
      LogUtils.d("PreferencesManager", "Retrieved sort option: " + option);
      return option;
    } catch (IllegalArgumentException e) {
      LogUtils.w(
          "PreferencesManager", "Invalid sort option name: " + sortOptionName + ", using default");
      return FileSortOption.NAME;
    }
  }

  /**
   * Saves the directories-first flag.
   *
   * @param directoriesFirst Whether to show directories first
   */
  public void saveDirectoriesFirst(boolean directoriesFirst) {
    LogUtils.d("PreferencesManager", "Saving directories first: " + directoriesFirst);
    preferences.edit().putBoolean(KEY_DIRECTORIES_FIRST, directoriesFirst).apply();
  }

  /**
   * Gets the saved directories-first flag.
   *
   * @return The saved directories-first flag, or true if none is saved
   */
  public boolean getDirectoriesFirst() {
    boolean directoriesFirst = preferences.getBoolean(KEY_DIRECTORIES_FIRST, true);
    LogUtils.d("PreferencesManager", "Retrieved directories first: " + directoriesFirst);
    return directoriesFirst;
  }

  /**
   * Saves the show-hidden-files flag.
   *
   * @param showHiddenFiles Whether to show hidden files
   */
  public void saveShowHiddenFiles(boolean showHiddenFiles) {
    LogUtils.d("PreferencesManager", "Saving show hidden files: " + showHiddenFiles);
    preferences.edit().putBoolean(KEY_SHOW_HIDDEN_FILES, showHiddenFiles).apply();
  }

  /**
   * Gets the saved show-hidden-files flag.
   *
   * @return The saved show-hidden-files flag, or false if none is saved
   */
  public boolean getShowHiddenFiles() {
    boolean showHiddenFiles = preferences.getBoolean(KEY_SHOW_HIDDEN_FILES, false);
    LogUtils.d("PreferencesManager", "Retrieved show hidden files: " + showHiddenFiles);
    return showHiddenFiles;
  }

  /**
   * Saves the auth-required-for-access flag.
   *
   * @param required Whether authentication is required before opening a share
   */
  public void saveAuthRequiredForAccess(boolean required) {
    LogUtils.d("PreferencesManager", "Saving auth required for access: " + required);
    preferences.edit().putBoolean(KEY_AUTH_REQUIRED_FOR_ACCESS, required).apply();
  }

  /**
   * Gets the auth-required-for-access flag.
   *
   * @return true if authentication is required before opening a share, false by default
   */
  public boolean isAuthRequiredForAccess() {
    boolean required = preferences.getBoolean(KEY_AUTH_REQUIRED_FOR_ACCESS, false);
    LogUtils.d("PreferencesManager", "Retrieved auth required for access: " + required);
    return required;
  }

  /**
   * Saves the auth-required-for-password-reveal flag.
   *
   * @param required Whether authentication is required before revealing a saved password
   */
  public void saveAuthRequiredForPasswordReveal(boolean required) {
    LogUtils.d("PreferencesManager", "Saving auth required for password reveal: " + required);
    preferences.edit().putBoolean(KEY_AUTH_REQUIRED_FOR_PASSWORD_REVEAL, required).apply();
  }

  /**
   * Gets the auth-required-for-password-reveal flag.
   *
   * @return true if authentication is required before revealing a saved password, false by default
   */
  public boolean isAuthRequiredForPasswordReveal() {
    boolean required = preferences.getBoolean(KEY_AUTH_REQUIRED_FOR_PASSWORD_REVEAL, false);
    LogUtils.d("PreferencesManager", "Retrieved auth required for password reveal: " + required);
    return required;
  }

  /**
   * Saves the show-thumbnails flag.
   *
   * @param showThumbnails Whether to show file thumbnails
   */
  public void saveShowThumbnails(boolean showThumbnails) {
    LogUtils.d("PreferencesManager", "Saving show thumbnails: " + showThumbnails);
    preferences.edit().putBoolean(KEY_SHOW_THUMBNAILS, showThumbnails).apply();
  }

  /**
   * Gets the saved show-thumbnails flag.
   *
   * @return The saved show-thumbnails flag, or false if none is saved
   */
  public boolean getShowThumbnails() {
    boolean showThumbnails = preferences.getBoolean(KEY_SHOW_THUMBNAILS, false);
    LogUtils.d("PreferencesManager", "Retrieved show thumbnails: " + showThumbnails);
    return showThumbnails;
  }

  /**
   * Saves the maximum file size for thumbnail generation.
   *
   * @param maxSizeMb The maximum size in megabytes; 0 means no limit
   */
  public void saveThumbnailMaxSizeMb(int maxSizeMb) {
    LogUtils.d("PreferencesManager", "Saving thumbnail max size: " + maxSizeMb + " MB");
    preferences.edit().putInt(KEY_THUMBNAIL_MAX_SIZE_MB, maxSizeMb).apply();
  }

  /**
   * Gets the maximum file size for thumbnail generation.
   *
   * @return The maximum size in megabytes (default {@link #DEFAULT_THUMBNAIL_MAX_SIZE_MB}); 0 means
   *     no limit
   */
  public int getThumbnailMaxSizeMb() {
    return preferences.getInt(KEY_THUMBNAIL_MAX_SIZE_MB, DEFAULT_THUMBNAIL_MAX_SIZE_MB);
  }

  /**
   * Saves whether thumbnails should only be loaded when the user taps their placeholder.
   *
   * @param lazyLoadOnTap Whether to load thumbnails on tap only
   */
  public void saveThumbnailLazyLoadOnTap(boolean lazyLoadOnTap) {
    LogUtils.d("PreferencesManager", "Saving thumbnail lazy load on tap: " + lazyLoadOnTap);
    preferences.edit().putBoolean(KEY_THUMBNAIL_LAZY_LOAD_ON_TAP, lazyLoadOnTap).apply();
  }

  /**
   * Gets whether thumbnails should only be loaded when the user taps their placeholder.
   *
   * @return true if thumbnails load on tap only, false by default
   */
  public boolean isThumbnailLazyLoadOnTap() {
    return preferences.getBoolean(KEY_THUMBNAIL_LAZY_LOAD_ON_TAP, false);
  }
}
