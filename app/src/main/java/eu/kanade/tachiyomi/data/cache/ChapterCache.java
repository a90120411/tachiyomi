package eu.kanade.tachiyomi.data.cache;

import android.content.Context;
import android.text.format.Formatter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.jakewharton.disklrucache.DiskLruCache;
import com.squareup.okhttp.Response;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.util.List;

import eu.kanade.tachiyomi.data.source.model.Page;
import eu.kanade.tachiyomi.util.DiskUtils;
import okio.BufferedSink;
import okio.Okio;
import rx.Observable;

/**
 * Class used to create chapter cache
 * For each image in a chapter a file is created
 * For each chapter a Json list is created and converted to a file.
 * The files are in format *md5key*.0
 */
public class ChapterCache {

    /** Name of cache directory. */
    private static final String PARAMETER_CACHE_DIRECTORY = "chapter_disk_cache";

    /** Application cache version. */
    private static final int PARAMETER_APP_VERSION = 1;

    /** The number of values per cache entry. Must be positive. */
    private static final int PARAMETER_VALUE_COUNT = 1;

    /** The maximum number of bytes this cache should use to store. */
    private static final int PARAMETER_CACHE_SIZE = 75 * 1024 * 1024;

    /** Interface to global information about an application environment. */
    private final Context context;

    /** Google Json class used for parsing JSON files. */
    private final Gson gson;

    /** Cache class used for cache management. */
    private DiskLruCache diskCache;

    /** Page list collection used for deserializing from JSON. */
    private final Type pageListCollection;

    /**
     * Constructor of ChapterCache.
     * @param context application environment interface.
     */
    public ChapterCache(Context context) {
        this.context = context;

        // Initialize Json handler.
        gson = new Gson();

        // Try to open cache in default cache directory.
        try {
            diskCache = DiskLruCache.open(
                    new File(context.getCacheDir(), PARAMETER_CACHE_DIRECTORY),
                    PARAMETER_APP_VERSION,
                    PARAMETER_VALUE_COUNT,
                    PARAMETER_CACHE_SIZE
            );
        } catch (IOException e) {
            // Do Nothing.
        }

        pageListCollection = new TypeToken<List<Page>>() {}.getType();
    }

    /**
     * Returns directory of cache.
     * @return directory of cache.
     */
    public File getCacheDir() {
        return diskCache.getDirectory();
    }

    /**
     * Returns real size of directory.
     * @return real size of directory.
     */
    private long getRealSize() {
        return DiskUtils.getDirectorySize(getCacheDir());
    }

    /**
     * Returns real size of directory in human readable format.
     * @return real size of directory.
     */
    public String getReadableSize() {
        return Formatter.formatFileSize(context, getRealSize());
    }

    /**
     * Remove file from cache.
     * @param file name of file "md5.0".
     * @return status of deletion for the file.
     */
    public boolean removeFileFromCache(String file) {
        // Make sure we don't delete the journal file (keeps track of cache).
        if (file.equals("journal") || file.startsWith("journal."))
            return false;

        try {
            // Remove the extension from the file to get the key of the cache
            String key = file.substring(0, file.lastIndexOf("."));
            // Remove file from cache.
            return diskCache.remove(key);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get page list from cache.
     * @param chapterUrl the url of the chapter.
     * @return an observable of the list of pages.
     */
    public Observable<List<Page>> getPageListFromCache(final String chapterUrl) {
        return Observable.fromCallable(() -> {
            // Initialize snapshot (a snapshot of the values for an entry).
            DiskLruCache.Snapshot snapshot = null;

            try {
                // Create md5 key and retrieve snapshot.
                String key = DiskUtils.hashKeyForDisk(chapterUrl);
                snapshot = diskCache.get(key);

                // Convert JSON string to list of objects.
                return gson.fromJson(snapshot.getString(0), pageListCollection);

            } finally {
                if (snapshot != null) {
                    snapshot.close();
                }
            }
        });
    }

    /**
     * Add page list to disk cache.
     * @param chapterUrl the url of the chapter.
     * @param pages list of pages.
     */
    public void putPageListToCache(final String chapterUrl, final List<Page> pages) {
        // Convert list of pages to json string.
        String cachedValue = gson.toJson(pages);

        // Initialize the editor (edits the values for an entry).
        DiskLruCache.Editor editor = null;

        // Initialize OutputStream.
        OutputStream outputStream = null;

        try {
            // Get editor from md5 key.
            String key = DiskUtils.hashKeyForDisk(chapterUrl);
            editor = diskCache.edit(key);
            if (editor == null) {
                return;
            }

            // Write chapter urls to cache.
            outputStream = new BufferedOutputStream(editor.newOutputStream(0));
            outputStream.write(cachedValue.getBytes());
            outputStream.flush();

            diskCache.flush();
            editor.commit();
        } catch (Exception e) {
            // Do Nothing.
        } finally {
            if (editor != null) {
                editor.abortUnlessCommitted();
            }
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException ignore) {
                    // Do Nothing.
                }
            }
        }
    }

    /**
     * Check if image is in cache.
     * @param imageUrl url of image.
     * @return true if in cache otherwise false.
     */
    public boolean isImageInCache(final String imageUrl) {
        try {
            return diskCache.get(DiskUtils.hashKeyForDisk(imageUrl)) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Get image path from url.
     * @param imageUrl url of image.
     * @return path of image.
     */
    public String getImagePath(final String imageUrl) {
        try {
            // Get file from md5 key.
            String imageName = DiskUtils.hashKeyForDisk(imageUrl) + ".0";
            File file = new File(diskCache.getDirectory(), imageName);
            return file.getCanonicalPath();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Add image to cache.
     * @param imageUrl url of image.
     * @param response http response from page.
     * @throws IOException image error.
     */
    public void putImageToCache(final String imageUrl, final Response response) throws IOException {
        // Initialize editor (edits the values for an entry).
        DiskLruCache.Editor editor = null;

        // Initialize BufferedSink (used for small writes).
        BufferedSink sink = null;

        try {
            // Get editor from md5 key.
            String key = DiskUtils.hashKeyForDisk(imageUrl);
            editor = diskCache.edit(key);
            if (editor == null) {
                throw new IOException("Unable to edit key");
            }

            // Initialize OutputStream and write image.
            OutputStream outputStream = new BufferedOutputStream(editor.newOutputStream(0));
            sink = Okio.buffer(Okio.sink(outputStream));
            sink.writeAll(response.body().source());

            diskCache.flush();
            editor.commit();
        } catch (Exception e) {
            response.body().close();
            throw new IOException("Unable to save image");
        } finally {
            if (editor != null) {
                editor.abortUnlessCommitted();
            }
            if (sink != null) {
                sink.close();
            }
        }
    }

}
