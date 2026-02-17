// License: GPL. For details, see Readme.txt file.
package org.openstreetmap.gui.jmapviewer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import javax.imageio.ImageIO;

import org.openstreetmap.gui.jmapviewer.interfaces.TileJob;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoader;
import org.openstreetmap.gui.jmapviewer.interfaces.TileLoaderListener;

/**
 * A {@link TileLoader} implementation that loads tiles from OSM.
 *
 * @author Jan Peter Stotz
 */
public class OsmTileLoader implements TileLoader {

  private static final ThreadPoolExecutor jobDispatcher = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);

  private final class OsmTileJob implements TileJob {
    private final Tile tile;
    private InputStream input;
    private boolean force;

    private OsmTileJob(Tile tile) {
      this.tile = tile;
    }

    @Override
    public void run() {
      synchronized (tile) {
        if ((tile.isLoaded() && !tile.hasError()) || tile.isLoading())
          return;
        tile.loaded = false;
        tile.error = false;
        tile.loading = true;
      }
      try {
        URLConnection conn = loadTileFromOsm(tile);
        if (force) {
          conn.setUseCaches(false);
        }
        loadTileMetadata(tile, conn);
        if ("no-tile".equals(tile.getValue("tile-info"))) {
          tile.setError("No tile at this zoom level");
        } else {
          input = conn.getInputStream();
          try {
            tile.loadImage(input);

            // ========== OPSLAAN IN CACHE (ALTijd bij succes) ==========
            try {
              // Bepaal cache-map (gebruikersmap)
              String cachePad = System.getProperty("user.home") + "/.jmapviewer/cache/";
              File cacheMap = new File(cachePad);

              // Maak submappen voor zoomniveau: /cache/13/1234_5678.png
              File zoomDir = new File(cacheMap, String.valueOf(tile.getZoom()));
              if (!zoomDir.exists()) {
                zoomDir.mkdirs();
              }

              // Sla tegel op als [x]_[y].png
              File outputFile = new File(zoomDir, tile.getXtile() + "_" + tile.getYtile() + ".png");
              ImageIO.write(tile.getImage(), "png", outputFile);

              if (JMapViewer.debug) {
                System.out.println("Opgeslagen in cache: " + outputFile.getAbsolutePath());
              }
            } catch (Exception ex) {
              // Cache opslag mag nooit de applicatie breken
              if (JMapViewer.debug) {
                System.err.println("Kon tegel niet opslaan in cache: " + ex.getMessage());
              }
            }
            // ========== EINDE OPSLAAN ==========

          } finally {
            input.close();
            input = null;
          }
        }
        tile.setLoaded(true);
        listener.tileLoadingFinished(tile, true);

      } catch (IOException e) {
        // ========== FALLBACK: Probeer uit cache te laden ==========
        try {
          String cachePad = System.getProperty("user.home") + "/.jmapviewer/cache/";
          File cacheFile = new File(cachePad + tile.getZoom() + "/" + tile.getXtile() + "_" + tile.getYtile() + ".png");

          if (cacheFile.exists()) {
            // Laad uit cache-bestand
            tile.setImage(ImageIO.read(cacheFile));
            tile.setLoaded(true);
            tile.setError("");
            listener.tileLoadingFinished(tile, true);

            if (JMapViewer.debug) {
              System.out.println("Geladen uit cache: " + cacheFile.getAbsolutePath());
            }
            return; // Succes!
          }
        } catch (Exception cacheEx) {
          // Cache lezen mislukt, negeer
        }

        // Als cache ook niets heeft, gebruik dan de oude image in geheugen
        if (tile.getImage() != null) {
          tile.setError("");
          tile.setLoaded(true);
          listener.tileLoadingFinished(tile, true);
        } else {
          tile.setError(e.getMessage());
          listener.tileLoadingFinished(tile, false);
          try {
            System.err.println("Failed loading " + tile.getUrl() + ": " + e.getMessage());
          } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
          }
        }
        // ========== EINDE FALLBACK ==========

      } finally {
        tile.loading = false;
        tile.setLoaded(true);
      }
    }

    @Override
    public void submit() {
      submit(false);
    }

    @Override
    public void submit(boolean force) {
      this.force = force;
      jobDispatcher.execute(this);
    }
  }

  /**
   * Holds the HTTP headers. Insert e.g. User-Agent here when default should not
   * be used.
   */
  public Map<String, String> headers = new HashMap<>();

  public int timeoutConnect;
  public int timeoutRead;

  protected TileLoaderListener listener;

  public OsmTileLoader(TileLoaderListener listener) {
    this(listener, null);
  }

  public OsmTileLoader(TileLoaderListener listener, Map<String, String> headers) {
    this.headers.put("Accept", "text/html, image/png, image/jpeg, image/gif, */*");
    this.headers.put("User-Agent", "JMapViewer Java/" + System.getProperty("java.version"));
    if (headers != null) {
      this.headers.putAll(headers);
    }
    this.listener = listener;
  }

  @Override
  public TileJob createTileLoaderJob(final Tile tile) {
    return new OsmTileJob(tile);
  }

  protected URLConnection loadTileFromOsm(Tile tile) throws IOException {
    URL url;
    url = new URL(tile.getUrl());
    URLConnection urlConn = url.openConnection();
    if (urlConn instanceof HttpURLConnection) {
      prepareHttpUrlConnection((HttpURLConnection) urlConn);
    }
    return urlConn;
  }

  protected void loadTileMetadata(Tile tile, URLConnection urlConn) {
    String str = urlConn.getHeaderField("X-VE-TILEMETA-CaptureDatesRange");
    if (str != null) {
      tile.putValue("capture-date", str);
    }
    str = urlConn.getHeaderField("X-VE-Tile-Info");
    if (str != null) {
      tile.putValue("tile-info", str);
    }

    Long lng = urlConn.getExpiration();
    if (lng.equals(0L)) {
      try {
        str = urlConn.getHeaderField("Cache-Control");
        if (str != null) {
          for (String token : str.split(",")) {
            if (token.startsWith("max-age=")) {
              lng = Long.parseLong(token.substring(8)) * 1000 + System.currentTimeMillis();
            }
          }
        }
      } catch (NumberFormatException e) {
        // ignore malformed Cache-Control headers
        if (JMapViewer.debug) {
          System.err.println(e.getMessage());
        }
      }
    }
    if (!lng.equals(0L)) {
      tile.putValue("expires", lng.toString());
    }
  }

  protected void prepareHttpUrlConnection(HttpURLConnection urlConn) {
    for (Entry<String, String> e : headers.entrySet()) {
      urlConn.setRequestProperty(e.getKey(), e.getValue());
    }
    if (timeoutConnect != 0)
      urlConn.setConnectTimeout(timeoutConnect);
    if (timeoutRead != 0)
      urlConn.setReadTimeout(timeoutRead);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  @Override
  public void cancelOutstandingTasks() {
    for (Runnable item : jobDispatcher.getQueue()) {
      jobDispatcher.remove(item);
    }
  }

  /**
   * Sets the maximum number of concurrent connections the tile loader will do
   * 
   * @param num number of conncurent connections
   */
  public static void setConcurrentConnections(int num) {
    jobDispatcher.setMaximumPoolSize(num);
  }
}
