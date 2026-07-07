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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import de.schliweb.sambalite.R;
import de.schliweb.sambalite.SambaLiteApp;
import de.schliweb.sambalite.data.model.SmbConnection;
import de.schliweb.sambalite.data.model.SmbFileItem;
import de.schliweb.sambalite.data.repository.SmbRepository;
import de.schliweb.sambalite.util.EnhancedFileUtils;
import de.schliweb.sambalite.util.FileOpener;
import de.schliweb.sambalite.util.LogUtils;
import de.schliweb.sambalite.util.OpenFileCacheManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javax.inject.Inject;

/**
 * In-app lightbox viewer for common file types. Images support gallery-style swiping between the
 * viewable files passed in; text files render inline. Files are fetched into the shared open-file
 * cache (same cache the external "Open" flow uses), so repeated views are free.
 */
public class FileViewerActivity extends AppCompatActivity {

  private static final String EXTRA_CONNECTION = "extra_connection";
  private static final String EXTRA_FILES = "extra_files";
  private static final String EXTRA_START_INDEX = "extra_start_index";

  /** Cap for inline text rendering; larger files are truncated with a notice. */
  private static final long MAX_TEXT_BYTES = 1024 * 1024;

  private static final Set<String> IMAGE_EXTENSIONS =
      new HashSet<>(Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic"));
  private static final Set<String> TEXT_EXTENSIONS =
      new HashSet<>(
          Arrays.asList("txt", "md", "log", "json", "xml", "csv", "ini", "conf", "yaml", "yml"));
  private static final Set<String> AUDIO_EXTENSIONS =
      new HashSet<>(Arrays.asList("mp3", "wav", "flac", "ogg", "m4a"));

  private static final int PAGE_TYPE_IMAGE = 0;
  private static final int PAGE_TYPE_TEXT = 1;
  private static final int PAGE_TYPE_PDF = 2;
  private static final int PAGE_TYPE_AUDIO = 3;

  @Inject SmbRepository smbRepository;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final Set<PageHolder> pageHolders = new HashSet<>();
  private ArrayList<SmbFileItem> files;
  private SmbConnection connection;
  private ViewPager2 pager;
  private ActivityResultLauncher<String> saveCopyLauncher;

  /** Creates an intent showing {@code files} starting at {@code startIndex}. */
  public static @NonNull Intent createIntent(
      @NonNull Context context,
      @NonNull SmbConnection connection,
      @NonNull ArrayList<SmbFileItem> files,
      int startIndex) {
    Intent intent = new Intent(context, FileViewerActivity.class);
    intent.putExtra(EXTRA_CONNECTION, connection);
    intent.putExtra(EXTRA_FILES, files);
    intent.putExtra(EXTRA_START_INDEX, startIndex);
    return intent;
  }

  /** Returns true if the file can be shown by this viewer. */
  public static boolean isViewable(@NonNull SmbFileItem file) {
    return file.isFile()
        && (isImage(file.getName())
            || isText(file.getName())
            || isPdf(file.getName())
            || isAudio(file.getName()));
  }

  /** Returns true if the filename has a supported audio extension. */
  public static boolean isAudio(@Nullable String filename) {
    return AUDIO_EXTENSIONS.contains(extensionOf(filename));
  }

  /** Returns true if the filename is a PDF. */
  public static boolean isPdf(@Nullable String filename) {
    return "pdf".equals(extensionOf(filename));
  }

  /** Returns true if the filename has a supported image extension. */
  public static boolean isImage(@Nullable String filename) {
    return IMAGE_EXTENSIONS.contains(extensionOf(filename));
  }

  /** Returns true if the filename has a supported text extension. */
  public static boolean isText(@Nullable String filename) {
    return TEXT_EXTENSIONS.contains(extensionOf(filename));
  }

  private static String extensionOf(@Nullable String filename) {
    if (filename == null) return "";
    return EnhancedFileUtils.getFileExtension(filename).toLowerCase(Locale.ROOT);
  }

  @Override
  @SuppressWarnings("unchecked")
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    ((SambaLiteApp) getApplication()).getAppComponent().inject(this);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_file_viewer);

    connection = (SmbConnection) getIntent().getSerializableExtra(EXTRA_CONNECTION);
    files = (ArrayList<SmbFileItem>) getIntent().getSerializableExtra(EXTRA_FILES);
    int startIndex = getIntent().getIntExtra(EXTRA_START_INDEX, 0);
    if (connection == null || files == null || files.isEmpty()) {
      LogUtils.w("FileViewerActivity", "Missing connection or files; finishing");
      finish();
      return;
    }

    Toolbar toolbar = findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);
    if (getSupportActionBar() != null) {
      getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    pager = findViewById(R.id.viewer_pager);
    pager.setAdapter(new ViewerAdapter());
    pager.setCurrentItem(Math.min(Math.max(startIndex, 0), files.size() - 1), false);
    pager.registerOnPageChangeCallback(
        new ViewPager2.OnPageChangeCallback() {
          @Override
          public void onPageSelected(int position) {
            updateToolbar(position);
            pauseAllAudio();
          }
        });
    updateToolbar(pager.getCurrentItem());

    saveCopyLauncher =
        registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"),
            this::saveCopyTo);
  }

  @Override
  protected void onPause() {
    super.onPause();
    pauseAllAudio();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    for (PageHolder holder : pageHolders) {
      holder.releaseResources();
    }
    pageHolders.clear();
    executor.shutdown();
  }

  private void pauseAllAudio() {
    for (PageHolder holder : pageHolders) {
      holder.pauseAudio();
    }
  }

  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    getMenuInflater().inflate(R.menu.menu_file_viewer, menu);
    return true;
  }

  @Override
  public boolean onOptionsItemSelected(@NonNull MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      finish();
      return true;
    }
    SmbFileItem current = currentFile();
    if (item.getItemId() == R.id.action_open_external) {
      withCachedFile(
          current,
          localFile -> {
            if (!FileOpener.openFile(this, localFile)) {
              showSnackbar(getString(R.string.no_app_to_open_file));
            }
          });
      return true;
    } else if (item.getItemId() == R.id.action_share) {
      withCachedFile(current, localFile -> shareFile(localFile));
      return true;
    } else if (item.getItemId() == R.id.action_save_copy) {
      saveCopyLauncher.launch(current.getName());
      return true;
    }
    return super.onOptionsItemSelected(item);
  }

  private SmbFileItem currentFile() {
    return files.get(pager.getCurrentItem());
  }

  private void updateToolbar(int position) {
    if (getSupportActionBar() == null) return;
    getSupportActionBar().setTitle(files.get(position).getName());
    getSupportActionBar()
        .setSubtitle(
            files.size() > 1
                ? String.format(Locale.getDefault(), "%d / %d", position + 1, files.size())
                : null);
  }

  private void shareFile(File localFile) {
    Uri uri =
        androidx.core.content.FileProvider.getUriForFile(
            this, getPackageName() + ".fileprovider", localFile);
    Intent send = new Intent(Intent.ACTION_SEND);
    send.setType(de.schliweb.sambalite.util.MimeTypeUtils.getMimeType(localFile.getName()));
    send.putExtra(Intent.EXTRA_STREAM, uri);
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(Intent.createChooser(send, getString(R.string.share)));
  }

  private void saveCopyTo(@Nullable Uri target) {
    if (target == null) return;
    SmbFileItem current = currentFile();
    withCachedFile(
        current,
        localFile ->
            executor.execute(
                () -> {
                  try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    Files.copy(localFile.toPath(), out);
                    runOnUiThread(() -> showSnackbar(getString(R.string.save_copy_success)));
                  } catch (Exception e) {
                    LogUtils.e("FileViewerActivity", "Save copy failed: " + e.getMessage());
                    runOnUiThread(() -> showSnackbar(getString(R.string.save_copy_error)));
                  }
                }));
  }

  private void showSnackbar(String message) {
    Snackbar.make(pager, message, Snackbar.LENGTH_LONG).show();
  }

  /** Runs {@code action} with the locally cached copy of {@code item}, fetching it if needed. */
  private void withCachedFile(SmbFileItem item, Consumer<File> action) {
    fetchToCache(
        item,
        action,
        error -> showSnackbar(getString(R.string.viewer_error_loading, item.getName())));
  }

  /**
   * Fetches the file into the shared open-file cache, reusing a valid cached copy when present.
   * Callbacks run on the main thread; both are skipped if the activity is finishing.
   */
  private void fetchToCache(SmbFileItem item, Consumer<File> onSuccess, Consumer<String> onError) {
    executor.execute(
        () -> {
          File target = new File(OpenFileCacheManager.getCacheDir(this), item.getName());
          try {
            boolean cacheValid = false;
            if (target.exists() && target.length() > 0) {
              boolean sizeMatches = item.getSize() <= 0 || target.length() == item.getSize();
              boolean notStale =
                  item.getLastModified() == null
                      || target.lastModified() >= item.getLastModified().getTime();
              cacheValid = sizeMatches && notStale;
              if (!cacheValid) {
                target.delete();
              }
            }
            if (!cacheValid) {
              OpenFileCacheManager.enforceMaxSize(this, target);
              smbRepository.downloadFile(connection, item.getPath(), target);
            }
            postToUi(() -> onSuccess.accept(target));
          } catch (Exception e) {
            LogUtils.e(
                "FileViewerActivity", "Failed to fetch " + item.getName() + ": " + e.getMessage());
            target.delete();
            postToUi(() -> onError.accept(e.getMessage()));
          }
        });
  }

  private void postToUi(Runnable action) {
    runOnUiThread(
        () -> {
          if (!isFinishing() && !isDestroyed()) {
            action.run();
          }
        });
  }

  private static String formatMillis(int millis) {
    int totalSeconds = millis / 1000;
    return String.format(Locale.getDefault(), "%d:%02d", totalSeconds / 60, totalSeconds % 60);
  }

  private static void closeQuietly(
      @Nullable PdfRenderer renderer, @Nullable ParcelFileDescriptor fd) {
    if (renderer != null) {
      try {
        renderer.close();
      } catch (Exception ignored) {
        // already closed
      }
    }
    if (fd != null) {
      try {
        fd.close();
      } catch (Exception ignored) {
        // already closed
      }
    }
  }

  /** Renders one PDF page per row into a full-width bitmap with a white page background. */
  private class PdfPagesAdapter extends RecyclerView.Adapter<PdfPagesAdapter.PdfPageHolder> {
    private final PdfRenderer renderer;
    private final int pageWidth;
    private final int pageCount;

    PdfPagesAdapter(PdfRenderer renderer) {
      this.renderer = renderer;
      this.pageWidth = getResources().getDisplayMetrics().widthPixels;
      this.pageCount = renderer.getPageCount();
    }

    class PdfPageHolder extends RecyclerView.ViewHolder {
      final ImageView pageView;
      int boundPage = -1;

      PdfPageHolder(ImageView view) {
        super(view);
        pageView = view;
      }
    }

    @NonNull
    @Override
    public PdfPageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      ImageView view = new ImageView(parent.getContext());
      RecyclerView.LayoutParams params =
          new RecyclerView.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT, (int) (pageWidth * 1.3f));
      params.bottomMargin =
          (int) (8 * parent.getContext().getResources().getDisplayMetrics().density);
      view.setLayoutParams(params);
      view.setAdjustViewBounds(true);
      return new PdfPageHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull PdfPageHolder holder, int position) {
      holder.boundPage = position;
      holder.pageView.setImageDrawable(null);
      executor.execute(
          () -> {
            try {
              final Bitmap bitmap;
              synchronized (renderer) {
                try (PdfRenderer.Page page = renderer.openPage(position)) {
                  int height = Math.max(1, pageWidth * page.getHeight() / page.getWidth());
                  bitmap = Bitmap.createBitmap(pageWidth, height, Bitmap.Config.ARGB_8888);
                  bitmap.eraseColor(Color.WHITE);
                  page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                }
              }
              postToUi(
                  () -> {
                    if (holder.boundPage != position) return;
                    ViewGroup.LayoutParams params = holder.pageView.getLayoutParams();
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    holder.pageView.setLayoutParams(params);
                    holder.pageView.setImageBitmap(bitmap);
                  });
            } catch (Exception e) {
              // Renderer closed mid-render (page recycled) or a corrupt page; leave placeholder
              LogUtils.w("FileViewerActivity", "PDF page render skipped: " + e.getMessage());
            }
          });
    }

    @Override
    public int getItemCount() {
      return pageCount;
    }
  }

  /** One page per file: an image page, a scrollable text page, or a PDF page list. */
  private class ViewerAdapter extends RecyclerView.Adapter<PageHolder> {

    @Override
    public int getItemViewType(int position) {
      String name = files.get(position).getName();
      if (isImage(name)) return PAGE_TYPE_IMAGE;
      if (isPdf(name)) return PAGE_TYPE_PDF;
      if (isAudio(name)) return PAGE_TYPE_AUDIO;
      return PAGE_TYPE_TEXT;
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      int layout;
      if (viewType == PAGE_TYPE_IMAGE) {
        layout = R.layout.item_viewer_image;
      } else if (viewType == PAGE_TYPE_PDF) {
        layout = R.layout.item_viewer_pdf;
      } else if (viewType == PAGE_TYPE_AUDIO) {
        layout = R.layout.item_viewer_audio;
      } else {
        layout = R.layout.item_viewer_text;
      }
      return new PageHolder(
          LayoutInflater.from(parent.getContext()).inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
      holder.bind(files.get(position));
    }

    @Override
    public void onViewRecycled(@NonNull PageHolder holder) {
      holder.releaseResources();
    }

    @Override
    public int getItemCount() {
      return files.size();
    }
  }

  /** Holder for a viewer page; guards against recycled binds via the bound path. */
  private class PageHolder extends RecyclerView.ViewHolder {
    private final @Nullable ImageView imageView;
    private final @Nullable TextView textView;
    private final @Nullable RecyclerView pdfPages;
    private final @Nullable View audioControls;
    private final View progress;
    private final TextView errorView;
    private String boundPath;
    private @Nullable PdfRenderer pdfRenderer;
    private @Nullable ParcelFileDescriptor pdfFd;
    private @Nullable MediaPlayer mediaPlayer;
    private @Nullable Runnable audioTicker;

    PageHolder(@NonNull View itemView) {
      super(itemView);
      imageView = itemView.findViewById(R.id.viewer_image);
      textView = itemView.findViewById(R.id.viewer_text);
      pdfPages = itemView.findViewById(R.id.viewer_pdf_pages);
      audioControls = itemView.findViewById(R.id.viewer_audio_controls);
      progress = itemView.findViewById(R.id.viewer_progress);
      errorView = itemView.findViewById(R.id.viewer_error);
      pageHolders.add(this);
    }

    void bind(SmbFileItem item) {
      boundPath = item.getPath();
      releaseResources();
      progress.setVisibility(View.VISIBLE);
      errorView.setVisibility(View.GONE);
      if (imageView != null) imageView.setImageDrawable(null);
      if (textView != null) textView.setText("");
      if (audioControls != null) audioControls.setVisibility(View.GONE);

      fetchToCache(
          item,
          localFile -> {
            if (!item.getPath().equals(boundPath)) return; // recycled onto another file
            if (pdfPages != null) {
              loadPdf(item, localFile);
            } else if (audioControls != null) {
              loadAudio(item, localFile);
            } else if (imageView != null) {
              loadImage(item, localFile);
            } else {
              loadText(item, localFile);
            }
          },
          error -> {
            if (!item.getPath().equals(boundPath)) return;
            progress.setVisibility(View.GONE);
            errorView.setText(getString(R.string.viewer_error_loading, item.getName()));
            errorView.setVisibility(View.VISIBLE);
          });
    }

    private void loadAudio(SmbFileItem item, File localFile) {
      com.google.android.material.button.MaterialButton playButton =
          itemView.findViewById(R.id.viewer_audio_play);
      Slider seek = itemView.findViewById(R.id.viewer_audio_seek);
      TextView elapsed = itemView.findViewById(R.id.viewer_audio_elapsed);
      TextView duration = itemView.findViewById(R.id.viewer_audio_duration);

      MediaPlayer player = new MediaPlayer();
      mediaPlayer = player;
      try {
        player.setDataSource(localFile.getAbsolutePath());
      } catch (Exception e) {
        LogUtils.e("FileViewerActivity", "Audio open failed: " + e.getMessage());
        player.release();
        mediaPlayer = null;
        progress.setVisibility(View.GONE);
        errorView.setText(getString(R.string.viewer_error_loading, item.getName()));
        errorView.setVisibility(View.VISIBLE);
        return;
      }

      player.setOnPreparedListener(
          mp -> {
            if (!item.getPath().equals(boundPath) || mediaPlayer != mp) return;
            progress.setVisibility(View.GONE);
            audioControls.setVisibility(View.VISIBLE);
            int durationMs = Math.max(1, mp.getDuration());
            seek.setValueTo(durationMs);
            seek.setValue(0);
            duration.setText(formatMillis(durationMs));
            elapsed.setText(formatMillis(0));
          });
      player.setOnCompletionListener(
          mp -> {
            playButton.setIconResource(R.drawable.ic_play);
            playButton.setContentDescription(getString(R.string.audio_play));
          });
      player.setOnErrorListener(
          (mp, what, extra) -> {
            LogUtils.w("FileViewerActivity", "Audio playback error: " + what + "/" + extra);
            return false;
          });

      playButton.setOnClickListener(
          v -> {
            MediaPlayer current = mediaPlayer;
            if (current == null) return;
            if (current.isPlaying()) {
              current.pause();
              playButton.setIconResource(R.drawable.ic_play);
              playButton.setContentDescription(getString(R.string.audio_play));
            } else {
              current.start();
              playButton.setIconResource(R.drawable.ic_pause);
              playButton.setContentDescription(getString(R.string.audio_pause));
              startAudioTicker(seek, elapsed);
            }
          });
      seek.addOnChangeListener(
          (slider, value, fromUser) -> {
            MediaPlayer current = mediaPlayer;
            if (fromUser && current != null) {
              current.seekTo((int) value);
              elapsed.setText(formatMillis((int) value));
            }
          });

      player.prepareAsync();
    }

    private void startAudioTicker(Slider seek, TextView elapsed) {
      if (audioTicker != null) {
        itemView.removeCallbacks(audioTicker);
      }
      audioTicker =
          () -> {
            MediaPlayer current = mediaPlayer;
            if (current == null) return;
            try {
              if (current.isPlaying()) {
                int position = Math.min(current.getCurrentPosition(), (int) seek.getValueTo());
                seek.setValue(position);
                elapsed.setText(formatMillis(position));
                itemView.postDelayed(audioTicker, 500);
              }
            } catch (IllegalStateException ignored) {
              // player released mid-tick
            }
          };
      itemView.postDelayed(audioTicker, 500);
    }

    /** Pauses audio playback if this page is playing. */
    void pauseAudio() {
      MediaPlayer current = mediaPlayer;
      if (current != null) {
        try {
          if (current.isPlaying()) {
            current.pause();
            com.google.android.material.button.MaterialButton playButton =
                itemView.findViewById(R.id.viewer_audio_play);
            if (playButton != null) {
              playButton.setIconResource(R.drawable.ic_play);
              playButton.setContentDescription(getString(R.string.audio_play));
            }
          }
        } catch (IllegalStateException ignored) {
          // player released
        }
      }
    }

    private void releaseAudio() {
      if (audioTicker != null) {
        itemView.removeCallbacks(audioTicker);
        audioTicker = null;
      }
      if (mediaPlayer != null) {
        try {
          mediaPlayer.release();
        } catch (Exception ignored) {
          // already released
        }
        mediaPlayer = null;
      }
    }

    /** Releases all page-held resources (PDF renderer, audio player). */
    void releaseResources() {
      releasePdf();
      releaseAudio();
    }

    private void loadPdf(SmbFileItem item, File localFile) {
      executor.execute(
          () -> {
            ParcelFileDescriptor fd = null;
            PdfRenderer renderer = null;
            try {
              fd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY);
              renderer = new PdfRenderer(fd);
            } catch (Exception e) {
              LogUtils.e("FileViewerActivity", "PDF open failed: " + e.getMessage());
              closeQuietly(renderer, fd);
              postToUi(
                  () -> {
                    if (!item.getPath().equals(boundPath)) return;
                    progress.setVisibility(View.GONE);
                    errorView.setText(getString(R.string.viewer_error_loading, item.getName()));
                    errorView.setVisibility(View.VISIBLE);
                  });
              return;
            }
            final ParcelFileDescriptor openedFd = fd;
            final PdfRenderer openedRenderer = renderer;
            postToUi(
                () -> {
                  if (!item.getPath().equals(boundPath)) {
                    closeQuietly(openedRenderer, openedFd);
                    return;
                  }
                  releasePdf();
                  pdfRenderer = openedRenderer;
                  pdfFd = openedFd;
                  progress.setVisibility(View.GONE);
                  pdfPages.setLayoutManager(new LinearLayoutManager(FileViewerActivity.this));
                  pdfPages.setAdapter(new PdfPagesAdapter(openedRenderer));
                });
          });
    }

    /** Detaches and closes any open PDF renderer and its file descriptor. */
    void releasePdf() {
      if (pdfPages != null) {
        pdfPages.setAdapter(null);
      }
      if (pdfRenderer != null) {
        synchronized (pdfRenderer) {
          try {
            pdfRenderer.close();
          } catch (Exception ignored) {
            // already closed
          }
        }
        pdfRenderer = null;
      }
      if (pdfFd != null) {
        try {
          pdfFd.close();
        } catch (Exception ignored) {
          // already closed
        }
        pdfFd = null;
      }
    }

    private void loadImage(SmbFileItem item, File localFile) {
      int screenWidth = getResources().getDisplayMetrics().widthPixels;
      executor.execute(
          () -> {
            try {
              ImageDecoder.Source source = ImageDecoder.createSource(localFile);
              Drawable drawable =
                  ImageDecoder.decodeDrawable(
                      source,
                      (decoder, info, src) -> {
                        // Downsample very large images to roughly 2x the screen width
                        int width = info.getSize().getWidth();
                        int maxWidth = screenWidth * 2;
                        if (width > maxWidth && maxWidth > 0) {
                          decoder.setTargetSampleSize(
                              Math.max(1, (int) Math.ceil((double) width / maxWidth)));
                        }
                      });
              postToUi(
                  () -> {
                    if (!item.getPath().equals(boundPath)) return;
                    progress.setVisibility(View.GONE);
                    imageView.setImageDrawable(drawable);
                    if (drawable instanceof AnimatedImageDrawable animated) {
                      animated.start();
                    }
                  });
            } catch (Exception e) {
              LogUtils.e("FileViewerActivity", "Image decode failed: " + e.getMessage());
              postToUi(
                  () -> {
                    if (!item.getPath().equals(boundPath)) return;
                    progress.setVisibility(View.GONE);
                    errorView.setText(getString(R.string.viewer_error_loading, item.getName()));
                    errorView.setVisibility(View.VISIBLE);
                  });
            }
          });
    }

    private void loadText(SmbFileItem item, File localFile) {
      executor.execute(
          () -> {
            try {
              StringBuilder builder = new StringBuilder();
              boolean truncated;
              try (InputStreamReader reader =
                  new InputStreamReader(new FileInputStream(localFile), StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                long read = 0;
                int n;
                while ((n = reader.read(buffer)) != -1 && read < MAX_TEXT_BYTES) {
                  builder.append(buffer, 0, n);
                  read += n;
                }
                truncated = n != -1;
              }
              if (truncated) {
                builder.append(getString(R.string.viewer_text_truncated));
              }
              String text = builder.toString();
              postToUi(
                  () -> {
                    if (!item.getPath().equals(boundPath)) return;
                    progress.setVisibility(View.GONE);
                    textView.setText(text);
                  });
            } catch (Exception e) {
              LogUtils.e("FileViewerActivity", "Text read failed: " + e.getMessage());
              postToUi(
                  () -> {
                    if (!item.getPath().equals(boundPath)) return;
                    progress.setVisibility(View.GONE);
                    errorView.setText(getString(R.string.viewer_error_loading, item.getName()));
                    errorView.setVisibility(View.VISIBLE);
                  });
            }
          });
    }
  }
}
