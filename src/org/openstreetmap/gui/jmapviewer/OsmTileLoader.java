// License: GPL. For details, see Readme.txt file.
package org.openstreetmap.gui.jmapviewer;

import java.util.logging.Logger;
import java.util.logging.Level;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.net.URISyntaxException;

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
 * A {@link TileLoader} implementation that loads tiles from OSM. Offline cache
 * added. (RK)
 *
 * @author Jan Peter Stotz, RSH Kwee
 */
public class OsmTileLoader implements TileLoader {
  private static final Logger LOGGER = Logger.getLogger("");
  private Level loglevel = Level.FINE;
  private static final ThreadPoolExecutor jobDispatcher = (ThreadPoolExecutor) Executors.newFixedThreadPool(8);

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

  protected URLConnection loadTileFromOsm(Tile tile) throws IOException {
    URL url;
    try {
      // 🔧 FIX: Gebruik URI voor Java 20+ compatibiliteit
      String urlString = tile.getUrl();
      if (JMapViewer.debug) {
        System.err.println("Loading tile from URL: " + urlString);
      }
      url = new URI(urlString).toURL();
    } catch (URISyntaxException e) {
      throw new IOException("Ongeldige tile URL: " + tile.getUrl(), e);
    } catch (IllegalArgumentException e) {
      throw new IOException("Ongeldige URL format: " + tile.getUrl(), e);
    }

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
        LOGGER.log(Level.FINE, e.getMessage());
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

  /**
   * Private class OsmTileJob
   */
  private final class OsmTileJob implements TileJob {
    private final Tile tile;
    private InputStream input;
    private boolean force;
    private String cachePad = System.getProperty("user.home") + "/.jmapviewer/cache/";

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

            // ========== Store always in cache ==========
            try {
              // Determine storage directory
              File cacheMap = new File(cachePad);

              // Create sub directories according to Zoom level: /cache/13/1234_5678.png
              File zoomDir = new File(cacheMap, String.valueOf(tile.getZoom()));
              if (!zoomDir.exists()) {
                zoomDir.mkdirs();
              }

              // Sla tegel op als [x]_[y].png
              File outputFile = new File(zoomDir, tile.getXtile() + "_" + tile.getYtile() + ".png");
              ImageIO.write(tile.getImage(), "png", outputFile);

              LOGGER.log(loglevel, "Stored in cache: " + outputFile.getAbsolutePath());

              // Bij opslaan - sla ook metadata op
              try {
                // Sla tegel op
                outputFile = new File(zoomDir, tile.getXtile() + "_" + tile.getYtile() + ".png");
                ImageIO.write(tile.getImage(), "png", outputFile);

                if (loglevel == Level.INFO) {
                  // SLA OOK METADATA OP - voor debugging en fallback
                  File metaFile = new File(zoomDir, tile.getXtile() + "_" + tile.getYtile() + ".meta");
                  try (PrintWriter out = new PrintWriter(metaFile)) {
                    out.println("url=" + tile.getUrl());
                    out.println("zoom=" + tile.getZoom());
                    out.println("x=" + tile.getXtile());
                    out.println("y=" + tile.getYtile());
                    out.println("tileSource=" + tile.getTileSource().getName());
                    out.println("timestamp=" + System.currentTimeMillis());
                  }
                }
              } catch (Exception ex) {
                // Do nothing
              }

            } catch (Exception ex) {
              // Cache storage may not break code ...
              LOGGER.log(loglevel, "Kon tegel niet opslaan in cache: " + ex.getMessage());
            }
            // ========== End Storage ==========

          } finally {
            input.close();
            input = null;
          }
        }
        tile.setLoaded(true);
        listener.tileLoadingFinished(tile, true);

      } catch (IOException e) {
        // ========== FALLBACK: Try loading from cache ==========
        try {
          // Maak een file:// URL naar je cache-bestand
          File cacheFile = new File(cachePad + tile.getZoom() + "/" + tile.getXtile() + "_" + tile.getYtile() + ".png");

          if (cacheFile.exists()) {
            // Vervang de tile URL tijdelijk
            URL originalUrl = null;
            try {
              originalUrl = new URI(tile.getUrl()).toURL();
              java.lang.reflect.Field urlField = tile.getClass().getDeclaredField("url");
              urlField.setAccessible(true);
              urlField.set(tile, cacheFile.toURI().toURL());
            } catch (Exception e1) {
              // Reflectie faalt, probeer andere aanpak
            }

            // Laad via de normale flow
            URLConnection conn = cacheFile.toURI().toURL().openConnection();
            tile.loadImage(conn.getInputStream());

            // Zet de originele URL terug
            if (originalUrl != null) {
              try {
                java.lang.reflect.Field urlField = tile.getClass().getDeclaredField("url");
                urlField.setAccessible(true);
                urlField.set(tile, originalUrl);
              } catch (Exception e2) {
              }
            }

            tile.setLoaded(true);
            listener.tileLoadingFinished(tile, true);
            return;
          }
        } catch (Exception e3) {
          e.printStackTrace();
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
}
