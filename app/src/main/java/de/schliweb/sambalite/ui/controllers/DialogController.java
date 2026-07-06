/*
 * Copyright 2025 Christian Kierdorf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package de.schliweb.sambalite.ui.controllers;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.sync.SyncConfig;
import de.schliweb.sambalite.sync.SyncDirection;
import de.schliweb.sambalite.ui.*;
import de.schliweb.sambalite.ui.controllers.FileOperationsController.FileOperationRequester;
import de.schliweb.sambalite.ui.dialogs.DialogHelper;
import de.schliweb.sambalite.ui.operations.FileOperationCallbacks;
import de.schliweb.sambalite.ui.operations.FileOperationsViewModel;
import de.schliweb.sambalite.util.LogUtils;
import java.lang.ref.WeakReference;
import java.util.List;
import lombok.Setter;

/**
 * Controller for managing dialogs in the FileBrowserActivity. Handles file operation dialogs,
 * search dialogs, sort dialogs, and upload/download dialogs.
 *
 * <p>The Activity context is held via a {@link WeakReference} to prevent memory leaks when
 * background threads outlive the Activity lifecycle.
 */
public class DialogController {

  private final WeakReference<Context> contextRef;
  private final FileListViewModel fileListViewModel;
  private final FileOperationsViewModel fileOperationsViewModel;
  private final FileBrowserUIState uiState;

  @Setter private FileOperationRequester fileOperationRequester;

  @Setter private FileOperationCallback fileOperationCallback;

  @Setter private SearchCallback searchCallback;

  @Setter private UploadCallback uploadCallback;

  @Setter private SyncSetupCallback syncSetupCallback;

  @Setter private FolderSyncCallback folderSyncCallback;

  @Setter private FileOpenCallback fileOpenCallback;

  @Setter private MultiSelectCallback multiSelectCallback;

  /**
   * User feedback provider for showing success, error, and info messages. This provides a
   * standardized approach to user feedback across controllers.
   */
  @Setter private UserFeedbackProvider userFeedbackProvider;

  /**
   * Returns the context if still available, or {@code null} if the Activity has been destroyed and
   * garbage-collected.
   */
  @Nullable
  private Context getContext() {
    return contextRef.get();
  }

  /**
   * Creates a new DialogController.
   *
   * @param context The context
   * @param fileListViewModel The FileListViewModel for file list operations
   * @param fileOperationsViewModel The FileOperationsViewModel for file operations
   * @param searchViewModel The SearchViewModel for search operations
   * @param uiState The shared UI state
   */
  public DialogController(
      @NonNull Context context,
      @NonNull FileListViewModel fileListViewModel,
      @NonNull FileOperationsViewModel fileOperationsViewModel,
      @NonNull SearchViewModel searchViewModel,
      @NonNull FileBrowserUIState uiState) {
    this.contextRef = new WeakReference<>(context);
    this.fileListViewModel = fileListViewModel;
    this.fileOperationsViewModel = fileOperationsViewModel;
    this.uiState = uiState;
  }

  public DialogController(
      @NonNull Context context,
      @NonNull ShareReceiverViewModel viewModel,
      @NonNull FileBrowserUIState uiState) {
    this.contextRef = new WeakReference<>(context);
    // For ShareReceiverActivity, we do not need file list or operations view models.
    // Instead, we only need the ShareReceiverViewModel and the UI state.
    this.fileListViewModel = null;
    this.fileOperationsViewModel = null;
    this.uiState = uiState;
    LogUtils.d("DialogController", "Created for ShareReceiverActivity");
  }

  /**
   * Shows the bottom action sheet for a file or folder: header with icon and name, then the
   * available actions.
   *
   * @param file The file to show actions for
   */
  public void showFileOptionsDialog(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing file actions sheet for: " + file.getName());

    // Store the selected file in the UI state
    uiState.setSelectedFile(file);

    com.google.android.material.bottomsheet.BottomSheetDialog sheet =
        new com.google.android.material.bottomsheet.BottomSheetDialog(context);
    android.view.View content =
        android.view.LayoutInflater.from(context).inflate(R.layout.bottom_sheet_file_actions, null);
    sheet.setContentView(content);

    android.widget.ImageView icon = content.findViewById(R.id.sheet_file_icon);
    android.widget.TextView name = content.findViewById(R.id.sheet_file_name);
    name.setText(file.getName());
    icon.setImageResource(
        file.isDirectory()
            ? android.R.drawable.ic_menu_more
            : de.schliweb.sambalite.ui.FileAdapter.getFileIcon(file.getName()));

    boolean canSync = file.isDirectory() && folderSyncCallback != null;
    boolean hasSync = canSync && folderSyncCallback.hasSyncConfig(file);

    bindSheetAction(
        sheet,
        content,
        R.id.action_open,
        !file.isDirectory(),
        () -> {
          if (fileOpenCallback != null) {
            fileOpenCallback.onOpenRequested(file);
          }
        });
    bindSheetAction(
        sheet,
        content,
        R.id.action_download,
        true,
        () -> {
          if (fileOperationCallback != null) {
            fileOperationCallback.onDownloadRequested(file);
          }
        });
    bindSheetAction(
        sheet,
        content,
        R.id.action_rename,
        true,
        () -> {
          if (fileOperationRequester != null) {
            fileOperationRequester.requestFileRename(file);
          }
          showRenameFileDialog(file);
        });
    bindSheetAction(sheet, content, R.id.action_info, true, () -> showFileInfoSheet(file));
    bindSheetAction(
        sheet,
        content,
        R.id.action_sync_setup,
        canSync && !hasSync,
        () -> folderSyncCallback.onSetupSyncRequested(file));
    bindSheetAction(
        sheet,
        content,
        R.id.action_sync_now,
        hasSync,
        () -> folderSyncCallback.onSyncNowRequested(file));
    bindSheetAction(
        sheet,
        content,
        R.id.action_sync_edit,
        hasSync,
        () -> folderSyncCallback.onEditSyncRequested(file));
    bindSheetAction(
        sheet,
        content,
        R.id.action_sync_remove,
        hasSync,
        () -> folderSyncCallback.onRemoveSyncRequested(file));
    bindSheetAction(
        sheet,
        content,
        R.id.action_delete,
        true,
        () -> {
          // The requester shows the delete confirmation dialog
          if (fileOperationRequester != null) {
            fileOperationRequester.requestFileDeletion(file);
          }
        });

    sheet.show();
  }

  /** Shows or hides one action row and wires its click to dismiss the sheet and run the action. */
  private void bindSheetAction(
      com.google.android.material.bottomsheet.BottomSheetDialog sheet,
      android.view.View content,
      int rowId,
      boolean visible,
      Runnable action) {
    android.view.View row = content.findViewById(rowId);
    if (row == null) return;
    row.setVisibility(visible ? android.view.View.VISIBLE : android.view.View.GONE);
    if (visible) {
      row.setOnClickListener(
          v -> {
            sheet.dismiss();
            action.run();
          });
    }
  }

  /**
   * Shows the file information sheet: icon, name, type, location, size (files only), and last
   * modified time.
   */
  public void showFileInfoSheet(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing file info sheet for: " + file.getName());

    com.google.android.material.bottomsheet.BottomSheetDialog sheet =
        new com.google.android.material.bottomsheet.BottomSheetDialog(context);
    android.view.View content =
        android.view.LayoutInflater.from(context).inflate(R.layout.bottom_sheet_file_info, null);
    sheet.setContentView(content);

    android.widget.ImageView icon = content.findViewById(R.id.info_icon);
    android.widget.TextView name = content.findViewById(R.id.info_name);
    android.widget.TextView typeValue = content.findViewById(R.id.info_type_value);
    android.widget.TextView locationValue = content.findViewById(R.id.info_location_value);
    android.view.View sizeRow = content.findViewById(R.id.info_size_row);
    android.widget.TextView sizeValue = content.findViewById(R.id.info_size_value);
    android.widget.TextView modifiedValue = content.findViewById(R.id.info_modified_value);

    icon.setImageResource(
        file.isDirectory()
            ? android.R.drawable.ic_menu_more
            : de.schliweb.sambalite.ui.FileAdapter.getFileIcon(file.getName()));
    name.setText(file.getName());
    typeValue.setText(describeFileType(context, file));
    locationValue.setText(file.getPath());
    if (file.isFile()) {
      sizeRow.setVisibility(android.view.View.VISIBLE);
      sizeValue.setText(android.text.format.Formatter.formatFileSize(context, file.getSize()));
    } else {
      sizeRow.setVisibility(android.view.View.GONE);
    }
    modifiedValue.setText(
        file.getLastModified() != null
            ? android.text.format.DateFormat.format("MMM dd, yyyy HH:mm", file.getLastModified())
            : "—");

    sheet.show();
  }

  /** Human-readable type: "Folder", "PDF file", or plain "File" without an extension. */
  private String describeFileType(Context context, SmbFileItem file) {
    if (file.isDirectory()) {
      return context.getString(R.string.info_type_folder);
    }
    String fileName = file.getName();
    int dot = fileName != null ? fileName.lastIndexOf('.') : -1;
    if (dot >= 0 && dot < fileName.length() - 1) {
      String ext = fileName.substring(dot + 1).toUpperCase(java.util.Locale.ROOT);
      return context.getString(R.string.info_type_file_with_ext, ext);
    }
    return context.getString(R.string.info_type_file);
  }

  /**
   * Shows a dialog with options for multiple selected files.
   *
   * @param selectedItems The list of selected files
   */
  public void showMultiSelectOptionsDialog(@NonNull List<SmbFileItem> selectedItems) {
    Context context = getContext();
    if (context == null || selectedItems.isEmpty()) return;

    LogUtils.d(
        "DialogController",
        "Showing multi-select options dialog for " + selectedItems.size() + " items");

    // Check if folders are in the selection
    boolean hasFolders = false;
    for (SmbFileItem item : selectedItems) {
      if (item.isDirectory()) {
        hasFolders = true;
        break;
      }
    }

    String downloadLabel = context.getString(R.string.download);
    String deleteLabel = context.getString(R.string.delete);
    if (hasFolders) {
      String folderHint = context.getString(R.string.multi_download_includes_folders);
      downloadLabel += "\n" + folderHint;
    }

    String[] options = new String[] {downloadLabel, deleteLabel};

    new MaterialAlertDialogBuilder(context)
        .setTitle(context.getString(R.string.multi_select_options))
        .setItems(
            options,
            (dialog, which) -> {
              if (multiSelectCallback != null) {
                switch (which) {
                  case 0: // Download
                    multiSelectCallback.onMultiDownloadRequested(selectedItems);
                    break;
                  case 1: // Delete
                    multiSelectCallback.onMultiDeleteRequested(selectedItems);
                    break;
                }
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /**
   * Shows a dialog to rename a file.
   *
   * @param file The file to rename
   */
  public void showRenameFileDialog(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing rename file dialog for: " + file.getName());
    DialogHelper.showRenameDialog(
        context,
        file.getName(),
        newName -> {
          if (newName != null && !newName.isEmpty()) {
            // The requestFileRename method has already been called before showing this dialog
            // Now we just need to perform the actual rename operation
            fileOperationsViewModel.renameFile(file, newName, createRenameCallback());
          }
        });
  }

  /**
   * Shows a confirmation dialog for file deletion.
   *
   * @param file The file to delete
   */
  public void showDeleteFileConfirmationDialog(@NonNull SmbFileItem file) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d(
        "DialogController", "Showing delete file confirmation dialog for: " + file.getName());
    DialogHelper.showConfirmationDialog(
        context,
        context.getString(R.string.delete_file_dialog_title),
        context.getString(R.string.confirm_delete_file, file.getName()),
        (dialog, which) -> deleteFileWithFeedback(file));
  }

  /**
   * Deletes a file with feedback. Uses UserFeedbackProvider if available, falls back to
   * DialogHelper for backward compatibility.
   *
   * @param file The file to delete
   */
  private void deleteFileWithFeedback(SmbFileItem file) {
    LogUtils.d("DialogController", "Deleting file with feedback: " + file.getName());
    fileOperationsViewModel.deleteFile(
        file,
        (success, message) -> {
          Context context = getContext();
          if (context == null) return;

          if (success) {
            if (userFeedbackProvider != null) {
              userFeedbackProvider.showSuccess(context.getString(R.string.delete_success));
            } else {
              de.schliweb.sambalite.ui.utils.UIHelper.with((Activity) context)
                  .message(context.getString(R.string.delete_success))
                  .success()
                  .show();
            }
          } else {
            if (userFeedbackProvider != null) {
              userFeedbackProvider.showError(context.getString(R.string.delete_error), message);
            } else {
              DialogHelper.showErrorDialog(
                  context, context.getString(R.string.delete_error), message);
            }
          }
        });
  }

  /** Shows a dialog to create a new folder. */
  public void showCreateFolderDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing create folder dialog");
    DialogHelper.showInputDialog(
        context,
        context.getString(R.string.create_folder),
        context.getString(R.string.create_folder_dialog_title),
        context.getString(R.string.folder_name),
        folderName -> {
          if (folderName != null && !folderName.isEmpty()) {
            fileOperationsViewModel.createFolder(folderName, createFolderCallback());
          }
        });
  }

  /** Shows a dialog for sorting files. */
  public void showSortDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing sort dialog");

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sort, null);
    RadioGroup sortGroup = dialogView.findViewById(R.id.sort_type_radio_group);
    CompoundButton directoriesFirstCheckbox =
        dialogView.findViewById(R.id.directories_first_checkbox);
    CompoundButton showHiddenFilesCheckbox =
        dialogView.findViewById(R.id.show_hidden_files_checkbox);
    CompoundButton showThumbnailsCheckbox = dialogView.findViewById(R.id.show_thumbnails_checkbox);

    // Set initial values
    FileSortOption currentSortOption = fileListViewModel.getSortOption().getValue();
    boolean directoriesFirst =
        Boolean.TRUE.equals(fileListViewModel.getDirectoriesFirst().getValue());
    boolean showHiddenFiles =
        Boolean.TRUE.equals(fileListViewModel.getShowHiddenFiles().getValue());
    boolean showThumbnails = Boolean.TRUE.equals(fileListViewModel.getShowThumbnails().getValue());

    if (currentSortOption == FileSortOption.NAME) {
      sortGroup.check(R.id.radio_name);
    } else if (currentSortOption == FileSortOption.DATE) {
      sortGroup.check(R.id.radio_date);
    } else if (currentSortOption == FileSortOption.SIZE) {
      sortGroup.check(R.id.radio_size);
    }

    directoriesFirstCheckbox.setChecked(directoriesFirst);
    showHiddenFilesCheckbox.setChecked(showHiddenFiles);
    showThumbnailsCheckbox.setChecked(showThumbnails);

    new MaterialAlertDialogBuilder(context)
        .setView(dialogView)
        .setPositiveButton(
            R.string.ok,
            (dialog, which) -> {
              // Get selected sort option
              FileSortOption sortOption;
              int checkedId = sortGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_date) {
                sortOption = FileSortOption.DATE;
              } else if (checkedId == R.id.radio_size) {
                sortOption = FileSortOption.SIZE;
              } else {
                sortOption = FileSortOption.NAME;
              }

              // Apply sort settings
              fileListViewModel.setSortOption(sortOption);
              fileListViewModel.setDirectoriesFirst(directoriesFirstCheckbox.isChecked());
              fileListViewModel.setShowHiddenFiles(showHiddenFilesCheckbox.isChecked());
              fileListViewModel.setShowThumbnails(showThumbnailsCheckbox.isChecked());
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /** Shows a dialog for searching files. */
  public void showSearchDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing search dialog");
    DialogHelper.showSearchDialog(
        context,
        (query, searchType, includeSubfolders) -> {
          if (query != null && !query.isEmpty()) {
            if (searchCallback != null) {
              searchCallback.onSearchRequested(query, searchType, includeSubfolders);
            }
          }
        });
  }

  /** Starts the file-upload flow through the registered requester and callback. */
  public void triggerFileUpload() {
    LogUtils.d("DialogController", "File upload triggered");
    if (fileOperationRequester != null) {
      fileOperationRequester.requestFileUpload();
    }
    if (uploadCallback != null) {
      uploadCallback.onFileUploadRequested();
    }
  }

  /** Starts the folder-contents-upload flow through the registered requester and callback. */
  public void triggerFolderContentsUpload() {
    LogUtils.d("DialogController", "Folder contents upload triggered");
    if (fileOperationRequester != null) {
      fileOperationRequester.requestFolderContentsUpload();
    }
    if (uploadCallback != null) {
      uploadCallback.onFolderContentsUploadRequested();
    }
  }

  /**
   * Creates a callback for rename operations. Uses UserFeedbackProvider if available, falls back to
   * DialogHelper for backward compatibility.
   *
   * @return The callback
   */
  private FileOperationCallbacks.RenameFileCallback createRenameCallback() {
    return (success, message) -> {
      Context context = getContext();
      if (context == null) return;

      if (success) {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showSuccess(context.getString(R.string.rename_success));
        } else {
          de.schliweb.sambalite.ui.utils.UIHelper.with((Activity) context)
              .message(context.getString(R.string.rename_success))
              .success()
              .show();
        }
      } else {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showError(context.getString(R.string.rename_error), message);
        } else {
          DialogHelper.showErrorDialog(context, context.getString(R.string.rename_error), message);
        }
      }
    };
  }

  /**
   * Creates a callback for folder creation operations. Uses UserFeedbackProvider if available,
   * falls back to DialogHelper for backward compatibility.
   *
   * @return The callback
   */
  private FileOperationCallbacks.CreateFolderCallback createFolderCallback() {
    return (success, message) -> {
      Context context = getContext();
      if (context == null) return;

      if (success) {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showSuccess(context.getString(R.string.folder_created));
        } else {
          de.schliweb.sambalite.ui.utils.UIHelper.with((Activity) context)
              .message(context.getString(R.string.folder_created))
              .success()
              .show();
        }
      } else {
        if (userFeedbackProvider != null) {
          userFeedbackProvider.showError(
              context.getString(R.string.folder_creation_error), message);
        } else {
          DialogHelper.showErrorDialog(
              context, context.getString(R.string.folder_creation_error), message);
        }
      }
    };
  }

  /** Shows a dialog when no target folder is set for sharing. */
  public void showNeedsTargetFolderDialog() {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing needs target folder dialog");
    DialogHelper.showNeedsTargetFolderDialog(
        context,
        () -> {
          Context ctx = getContext();
          if (ctx == null) return;
          // Navigate to MainActivity to select folder
          Intent intent = new Intent(ctx, MainActivity.class);
          ctx.startActivity(intent);
          ((Activity) ctx).finish();
        },
        () -> {
          Context ctx = getContext();
          if (ctx == null) return;
          // Cancel and finish
          ((Activity) ctx).finish();
        });
  }

  /**
   * Shows a confirmation dialog before uploading shared files.
   *
   * @param fileCount Number of files to upload
   * @param targetFolder Target folder for upload
   * @param onUpload Callback when user confirms upload
   * @param onCancel Callback when user cancels upload
   */
  public void showShareUploadConfirmationDialog(
      int fileCount,
      @NonNull String targetFolder,
      @Nullable Runnable onUpload,
      @Nullable Runnable onCancel) {
    showShareUploadConfirmationDialog(fileCount, targetFolder, onUpload, null, onCancel);
  }

  /**
   * Shows a confirmation dialog before uploading shared files with an optional folder change
   * button.
   *
   * @param fileCount Number of files to upload
   * @param targetFolder Target folder for upload
   * @param onUpload Callback when user confirms upload
   * @param onChangeFolder Callback when user wants to change the target folder (may be null)
   * @param onCancel Callback when user cancels upload
   */
  public void showShareUploadConfirmationDialog(
      int fileCount,
      @NonNull String targetFolder,
      @NonNull Runnable onUpload,
      @NonNull Runnable onChangeFolder,
      @NonNull Runnable onCancel) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing share upload confirmation dialog with custom cancel");
    DialogHelper.showShareUploadConfirmationDialog(
        context, fileCount, targetFolder, onUpload, onChangeFolder, onCancel);
  }

  /**
   * Shows a dialog after successful uploads with option to view uploaded files.
   *
   * @param uploadedCount Number of successfully uploaded files
   * @param totalCount Total number of files attempted
   * @param failedCount Number of failed uploads
   * @param onViewFiles Callback when user wants to view uploaded files
   */
  public void showUploadCompleteDialog(
      int uploadedCount, int totalCount, int failedCount, @Nullable Runnable onViewFiles) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing upload complete dialog");
    DialogHelper.showUploadCompleteDialog(
        context,
        uploadedCount,
        totalCount,
        failedCount,
        onViewFiles,
        () -> {
          Context ctx = getContext();
          if (ctx == null) return;
          // Close and finish
          ((Activity) ctx).finish();
        });
  }

  /**
   * Shows a dialog when a file already exists during upload.
   *
   * @param fileName Name of the existing file
   * @param onOverwrite Callback when user chooses to overwrite
   * @param onCancel Callback when user cancels
   */
  public void showFileExistsDialog(
      @NonNull String fileName, @NonNull Runnable onOverwrite, @NonNull Runnable onCancel) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing file exists dialog for: " + fileName);
    DialogHelper.showFileExistsDialog(context, fileName, onOverwrite, onCancel);
  }

  /** Callback for opening a file with an external app. */
  public interface FileOpenCallback {
    void onOpenRequested(@NonNull SmbFileItem file);
  }

  /** Callback for multi-select file operations. */
  public interface MultiSelectCallback {
    void onMultiDownloadRequested(@NonNull List<SmbFileItem> files);

    void onMultiDeleteRequested(@NonNull List<SmbFileItem> files);
  }

  /** Callback for file operations. */
  public interface FileOperationCallback {
    /**
     * Called when a download is requested.
     *
     * @param file The file to download
     */
    void onDownloadRequested(@NonNull SmbFileItem file);
  }

  /** Callback for search operations. */
  public interface SearchCallback {
    /**
     * Called when a search is requested.
     *
     * @param query The search query
     * @param searchType The type of items to search for (0=all, 1=files only, 2=folders only)
     * @param includeSubfolders Whether to include subfolders in the search
     */
    void onSearchRequested(@NonNull String query, int searchType, boolean includeSubfolders);
  }

  /** Callback for upload operations. */
  public interface UploadCallback {
    /** Called when a file upload is requested. */
    void onFileUploadRequested();

    /** Called when a folder contents upload is requested. */
    void onFolderContentsUploadRequested();
  }

  /** Callback interface for folder-level sync operations from the context menu. */
  public interface FolderSyncCallback {
    /** Called when the user wants to set up sync for a folder. */
    void onSetupSyncRequested(@NonNull SmbFileItem folder);

    /** Called when the user wants to sync a folder immediately. */
    void onSyncNowRequested(@NonNull SmbFileItem folder);

    /** Called when the user wants to edit sync for a folder. */
    void onEditSyncRequested(@NonNull SmbFileItem folder);

    /** Called when the user wants to remove sync from a folder. */
    void onRemoveSyncRequested(@NonNull SmbFileItem folder);

    /** Checks if the given folder already has a sync configuration. */
    boolean hasSyncConfig(@NonNull SmbFileItem folder);
  }

  public interface SyncSetupCallback {
    /**
     * Called when sync setup is confirmed with the selected parameters.
     *
     * @param direction the sync direction
     * @param intervalMinutes the sync interval in minutes
     * @param remotePath the remote path
     */
    void onSyncSetupRequested(
        @NonNull SyncDirection direction,
        int intervalMinutes,
        @NonNull String remotePath,
        boolean wifiOnly,
        boolean mirror,
        boolean mirrorUseTrash);

    /** Called when the user wants to select a local folder for sync. */
    void onSyncFolderPickRequested();
  }

  /**
   * Shows the sync setup dialog.
   *
   * @param currentRemotePath the current remote path to pre-fill
   */
  public void showSyncSetupDialog(@NonNull String currentRemotePath) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing sync setup dialog");

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sync_setup, null);

    RadioGroup directionGroup = dialogView.findViewById(R.id.sync_direction_group);
    Spinner intervalSpinner = dialogView.findViewById(R.id.sync_interval_spinner);
    com.google.android.material.materialswitch.MaterialSwitch wifiOnlySwitch =
        dialogView.findViewById(R.id.sync_wifi_only_switch);
    com.google.android.material.materialswitch.MaterialSwitch mirrorSwitch =
        dialogView.findViewById(R.id.sync_mirror_switch);
    TextView mirrorWarning = dialogView.findViewById(R.id.sync_mirror_warning);
    View mirrorTrashRow = dialogView.findViewById(R.id.sync_mirror_trash_row);
    View mirrorTrashHint = dialogView.findViewById(R.id.sync_mirror_trash_hint);
    com.google.android.material.materialswitch.MaterialSwitch mirrorTrashSwitch =
        dialogView.findViewById(R.id.sync_mirror_use_trash_switch);
    TextView remotePathField = dialogView.findViewById(R.id.sync_remote_path);
    TextView folderDisplay = dialogView.findViewById(R.id.sync_local_folder_display);
    View selectFolderButton = dialogView.findViewById(R.id.sync_select_folder_button);

    // Mirror warning + trash row are only meaningful for one-way mirror; visibility follows state.
    Runnable updateMirrorWarning =
        () -> {
          int checked = directionGroup.getCheckedRadioButtonId();
          boolean oneWay =
              checked == R.id.radio_local_to_remote || checked == R.id.radio_remote_to_local;
          boolean active = mirrorSwitch.isChecked() && oneWay;
          mirrorWarning.setVisibility(active ? View.VISIBLE : View.GONE);
          mirrorTrashRow.setVisibility(active ? View.VISIBLE : View.GONE);
          mirrorTrashHint.setVisibility(active ? View.VISIBLE : View.GONE);
        };
    mirrorSwitch.setOnCheckedChangeListener((b, c) -> updateMirrorWarning.run());
    directionGroup.setOnCheckedChangeListener((g, id) -> updateMirrorWarning.run());
    updateMirrorWarning.run();

    // Pre-fill remote path and make it non-editable
    if (currentRemotePath != null && !currentRemotePath.isEmpty()) {
      remotePathField.setText(currentRemotePath);
    }
    remotePathField.setEnabled(false);

    // Setup interval spinner
    String[] intervalLabels = {
      context.getString(R.string.sync_interval_manual),
      context.getString(R.string.sync_interval_15min),
      context.getString(R.string.sync_interval_30min),
      context.getString(R.string.sync_interval_1h),
      context.getString(R.string.sync_interval_6h),
      context.getString(R.string.sync_interval_12h),
      context.getString(R.string.sync_interval_24h)
    };
    int[] intervalValues = {0, 15, 30, 60, 360, 720, 1440};

    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, intervalLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    intervalSpinner.setAdapter(adapter);
    intervalSpinner.setSelection(3); // Default: Every hour

    // Folder picker button
    selectFolderButton.setOnClickListener(
        v -> {
          if (syncSetupCallback != null) {
            syncSetupCallback.onSyncFolderPickRequested();
          }
        });

    // Store reference to folder display for updating from outside
    uiState.setSyncFolderDisplay(folderDisplay);

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sync_setup_title)
        .setView(dialogView)
        .setPositiveButton(
            R.string.save,
            (dialog, which) -> {
              // Get direction
              SyncDirection direction = SyncDirection.BIDIRECTIONAL;
              int checkedId = directionGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_local_to_remote) {
                direction = SyncDirection.LOCAL_TO_REMOTE;
              } else if (checkedId == R.id.radio_remote_to_local) {
                direction = SyncDirection.REMOTE_TO_LOCAL;
              }

              // Get interval
              int intervalMinutes = intervalValues[intervalSpinner.getSelectedItemPosition()];

              // Get wifiOnly
              boolean wifiOnly = wifiOnlySwitch.isChecked();

              // Get mirror flag (ignored for BIDIRECTIONAL)
              boolean mirror = mirrorSwitch.isChecked() && direction != SyncDirection.BIDIRECTIONAL;
              boolean mirrorUseTrash = mirrorTrashSwitch.isChecked();

              // Get remote path
              String remotePath = remotePathField.getText().toString().trim();

              if (syncSetupCallback != null) {
                syncSetupCallback.onSyncSetupRequested(
                    direction, intervalMinutes, remotePath, wifiOnly, mirror, mirrorUseTrash);
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }

  /**
   * Shows the sync edit dialog with pre-filled values from an existing config.
   *
   * @param config the existing sync configuration to edit
   */
  public void showSyncEditDialog(@NonNull SyncConfig config) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing sync edit dialog for config: " + config.getId());

    View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_sync_setup, null);

    RadioGroup directionGroup = dialogView.findViewById(R.id.sync_direction_group);
    Spinner intervalSpinner = dialogView.findViewById(R.id.sync_interval_spinner);
    com.google.android.material.materialswitch.MaterialSwitch wifiOnlySwitch =
        dialogView.findViewById(R.id.sync_wifi_only_switch);
    com.google.android.material.materialswitch.MaterialSwitch mirrorSwitch =
        dialogView.findViewById(R.id.sync_mirror_switch);
    TextView mirrorWarning = dialogView.findViewById(R.id.sync_mirror_warning);
    View mirrorTrashRow = dialogView.findViewById(R.id.sync_mirror_trash_row);
    View mirrorTrashHint = dialogView.findViewById(R.id.sync_mirror_trash_hint);
    com.google.android.material.materialswitch.MaterialSwitch mirrorTrashSwitch =
        dialogView.findViewById(R.id.sync_mirror_use_trash_switch);
    TextView remotePathField = dialogView.findViewById(R.id.sync_remote_path);
    TextView folderDisplay = dialogView.findViewById(R.id.sync_local_folder_display);
    View selectFolderButton = dialogView.findViewById(R.id.sync_select_folder_button);

    Runnable updateMirrorWarning =
        () -> {
          int checked = directionGroup.getCheckedRadioButtonId();
          boolean oneWay =
              checked == R.id.radio_local_to_remote || checked == R.id.radio_remote_to_local;
          boolean active = mirrorSwitch.isChecked() && oneWay;
          mirrorWarning.setVisibility(active ? View.VISIBLE : View.GONE);
          mirrorTrashRow.setVisibility(active ? View.VISIBLE : View.GONE);
          mirrorTrashHint.setVisibility(active ? View.VISIBLE : View.GONE);
        };
    mirrorSwitch.setOnCheckedChangeListener((b, c) -> updateMirrorWarning.run());
    directionGroup.setOnCheckedChangeListener((g, id) -> updateMirrorWarning.run());

    // Pre-fill remote path and make it non-editable in edit mode
    if (config.getRemotePath() != null && !config.getRemotePath().isEmpty()) {
      remotePathField.setText(config.getRemotePath());
    }
    remotePathField.setEnabled(false);

    // Pre-fill direction
    switch (config.getDirection()) {
      case LOCAL_TO_REMOTE:
        directionGroup.check(R.id.radio_local_to_remote);
        break;
      case REMOTE_TO_LOCAL:
        directionGroup.check(R.id.radio_remote_to_local);
        break;
      case BIDIRECTIONAL:
      default:
        directionGroup.check(R.id.radio_bidirectional);
        break;
    }

    // Setup interval spinner
    String[] intervalLabels = {
      context.getString(R.string.sync_interval_manual),
      context.getString(R.string.sync_interval_15min),
      context.getString(R.string.sync_interval_30min),
      context.getString(R.string.sync_interval_1h),
      context.getString(R.string.sync_interval_6h),
      context.getString(R.string.sync_interval_12h),
      context.getString(R.string.sync_interval_24h)
    };
    int[] intervalValues = {0, 15, 30, 60, 360, 720, 1440};

    ArrayAdapter<String> adapter =
        new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, intervalLabels);
    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    intervalSpinner.setAdapter(adapter);

    // Pre-select interval
    int currentInterval = config.getIntervalMinutes();
    int selectedIndex = 3; // default: 1h
    for (int i = 0; i < intervalValues.length; i++) {
      if (intervalValues[i] == currentInterval) {
        selectedIndex = i;
        break;
      }
    }
    intervalSpinner.setSelection(selectedIndex);

    // Pre-fill wifiOnly
    wifiOnlySwitch.setChecked(config.isWifiOnly());

    // Pre-fill mirror
    mirrorSwitch.setChecked(config.isMirror());
    mirrorTrashSwitch.setChecked(config.isMirrorUseTrash());
    updateMirrorWarning.run();

    // Pre-fill local folder display
    if (config.getLocalFolderDisplayName() != null
        && !config.getLocalFolderDisplayName().isEmpty()) {
      folderDisplay.setText(config.getLocalFolderDisplayName());
    }
    if (config.getLocalFolderUri() != null) {
      uiState.setSyncFolderUri(android.net.Uri.parse(config.getLocalFolderUri()));
      uiState.setSyncFolderDisplayName(config.getLocalFolderDisplayName());
    }

    // Folder picker button
    selectFolderButton.setOnClickListener(
        v -> {
          if (syncSetupCallback != null) {
            syncSetupCallback.onSyncFolderPickRequested();
          }
        });

    // Store reference to folder display for updating from outside
    uiState.setSyncFolderDisplay(folderDisplay);

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sync_edit_option)
        .setView(dialogView)
        .setPositiveButton(
            R.string.save,
            (dialog, which) -> {
              // Get direction
              SyncDirection direction = SyncDirection.BIDIRECTIONAL;
              int checkedId = directionGroup.getCheckedRadioButtonId();
              if (checkedId == R.id.radio_local_to_remote) {
                direction = SyncDirection.LOCAL_TO_REMOTE;
              } else if (checkedId == R.id.radio_remote_to_local) {
                direction = SyncDirection.REMOTE_TO_LOCAL;
              }

              // Get interval
              int intervalMinutes = intervalValues[intervalSpinner.getSelectedItemPosition()];

              // Get wifiOnly
              boolean wifiOnly = wifiOnlySwitch.isChecked();

              // Get mirror flag (ignored for BIDIRECTIONAL)
              boolean mirror = mirrorSwitch.isChecked() && direction != SyncDirection.BIDIRECTIONAL;
              boolean mirrorUseTrash = mirrorTrashSwitch.isChecked();

              // Get remote path
              String remotePath = remotePathField.getText().toString().trim();

              if (syncSetupCallback != null) {
                syncSetupCallback.onSyncSetupRequested(
                    direction, intervalMinutes, remotePath, wifiOnly, mirror, mirrorUseTrash);
              }
            })
        .setNegativeButton(
            R.string.cancel,
            (dialog, which) -> {
              // Clear editing state so the config is not removed on cancel
              uiState.setEditingSyncConfigId(null);
              uiState.setSyncFolderUri(null);
              uiState.setSyncFolderDisplayName(null);
              uiState.setSyncFolderDisplay(null);
            })
        .setOnCancelListener(
            dialog -> {
              // Also handle back button / outside tap dismissal
              uiState.setEditingSyncConfigId(null);
              uiState.setSyncFolderUri(null);
              uiState.setSyncFolderDisplayName(null);
              uiState.setSyncFolderDisplay(null);
            })
        .show();
  }

  /**
   * Shows a dialog to manage existing sync configurations.
   *
   * @param configs the list of sync configurations
   * @param onDelete callback when a config should be deleted
   * @param onToggle callback when a config should be enabled/disabled
   * @param onSyncNow callback when immediate sync is requested
   */
  public void showManageSyncConfigsDialog(
      @NonNull java.util.List<SyncConfig> configs,
      @NonNull java.util.function.Consumer<String> onDelete,
      @NonNull java.util.function.BiConsumer<String, Boolean> onToggle,
      @NonNull Runnable onSyncNow) {
    Context context = getContext();
    if (context == null) return;

    LogUtils.d("DialogController", "Showing manage sync configs dialog");

    if (configs.isEmpty()) {
      new MaterialAlertDialogBuilder(context)
          .setTitle(R.string.sync_manage_title)
          .setMessage(R.string.sync_no_configs)
          .setPositiveButton(R.string.ok, null)
          .show();
      return;
    }

    String[] items = new String[configs.size()];
    for (int i = 0; i < configs.size(); i++) {
      SyncConfig config = configs.get(i);
      String status =
          config.isEnabled()
              ? context.getString(R.string.sync_enabled)
              : context.getString(R.string.sync_disabled);
      String lastSync =
          config.getLastSyncTimestamp() > 0
              ? android.text.format.DateFormat.format(
                      "yyyy-MM-dd HH:mm", config.getLastSyncTimestamp())
                  .toString()
              : context.getString(R.string.sync_never);
      items[i] =
          config.getLocalFolderDisplayName()
              + " → "
              + config.getRemotePath()
              + "\n"
              + status
              + " | "
              + context.getString(R.string.sync_last_sync, lastSync);
    }

    new MaterialAlertDialogBuilder(context)
        .setTitle(R.string.sync_manage_title)
        .setItems(
            items,
            (dialog, which) -> {
              SyncConfig selected = configs.get(which);
              showSyncConfigOptionsDialog(selected, onDelete, onToggle);
            })
        .setNeutralButton(
            R.string.sync_now,
            (dialog, which) -> {
              if (onSyncNow != null) onSyncNow.run();
            })
        .setNegativeButton(R.string.close, null)
        .show();
  }

  /** Shows options for a single sync configuration. */
  private void showSyncConfigOptionsDialog(
      SyncConfig config,
      java.util.function.Consumer<String> onDelete,
      java.util.function.BiConsumer<String, Boolean> onToggle) {
    Context context = getContext();
    if (context == null) return;

    String toggleLabel =
        config.isEnabled()
            ? context.getString(R.string.sync_disabled)
            : context.getString(R.string.sync_enabled);
    String[] options = {toggleLabel, context.getString(R.string.delete)};

    new MaterialAlertDialogBuilder(context)
        .setTitle(config.getLocalFolderDisplayName())
        .setItems(
            options,
            (dialog, which) -> {
              switch (which) {
                case 0: // Toggle
                  if (onToggle != null) {
                    onToggle.accept(config.getId(), !config.isEnabled());
                  }
                  break;
                case 1: // Delete
                  if (onDelete != null) {
                    onDelete.accept(config.getId());
                  }
                  break;
              }
            })
        .setNegativeButton(R.string.cancel, null)
        .show();
  }
}
