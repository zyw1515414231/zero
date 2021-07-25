 package com.zero.zerolivewallpaper;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;
import com.google.android.material.snackbar.Snackbar;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;

import com.zero.zerolivewallpaper.async.CustomCreator;
import com.zero.zerolivewallpaper.async.MyAsync;
import com.zero.zerolivewallpaper.async.WallpaperDownloader;
import com.zero.zerolivewallpaper.components.CatalogAdapter;
import com.zero.zerolivewallpaper.components.InfoView;
import com.zero.zerolivewallpaper.components.MySwipeRefreshLayout;
import com.zero.zerolivewallpaper.data.Catalog;
import com.zero.zerolivewallpaper.data.CatalogItem;
import com.zero.zerolivewallpaper.services.SyncManager;
import com.zero.zerolivewallpaper.utils.InternalData;
import com.zero.zerolivewallpaper.utils.StorageHelper;
import com.theartofdev.edmodo.cropper.CropImage;

import java.io.File;
import java.util.List;

import static com.zero.zerolivewallpaper.Constants.LD_TIMESTAMP;
import static com.zero.zerolivewallpaper.Constants.PREF_CHECKSENS;
import static com.zero.zerolivewallpaper.Constants.PREF_CHECKSENS_DEFAULT;
import static com.zero.zerolivewallpaper.Constants.T_CATALOG_EXPIRATION;
import static com.zero.zerolivewallpaper.Utils.getTimestamp;
import static com.zero.zerolivewallpaper.Utils.openLWSetter;
import static com.zero.zerolivewallpaper.utils.StorageHelper.backgroundExist;
import static com.zero.zerolivewallpaper.utils.StorageHelper.getCacheFolder;

public class MainActivity extends AppCompatActivity implements MyAsync.MyAsyncInterface, SwipeRefreshLayout.OnRefreshListener {

    // Log
    private final String TAG = getClass().getSimpleName();

    // Constants
    public static final String EXTRA_DISABLE_NOTIFICATION = "disable_notification";

    // Layout
    private View rootView;
    private MySwipeRefreshLayout swipeRefreshLayout;
    private InfoView infoView;

    // Catalog
    private CatalogAdapter catalogAdapter;
    private Catalog catalog;

    // Broadcast listener
    private final IntentFilter broadcastFilter = new IntentFilter(SyncManager.ACTION_SYNC);
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                int result = intent.getIntExtra(SyncManager.EXTRA_RESULT, SyncManager.RESULT_FAIL);
                swipeRefreshLayout.setRefreshing(false); // Stop refresh layout
                if (result == SyncManager.RESULT_SUCCESS) {
                    // Reload local content
                    if (loadLocalContent()) {
                        refreshList();
                    } else {
                        // This error is very unlikely to happen
                        showCatalogDownloadError();
                    }
                } else if (result == SyncManager.RESULT_TIMEOUT) {
                    // Connection timeout
                    showTimeoutError();
                } else {
                    // Catalog sync failed
                    showCatalogDownloadError(); // Show catalog download error
                }
            }
        }
    };

    private Context context;
    private Uri imageUri;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        context = this;

        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        // Layout
        rootView = findViewById(R.id.main_container);
        GridView catalogList = (GridView) findViewById(R.id.catalog_grid);
        swipeRefreshLayout = (MySwipeRefreshLayout) rootView;
        infoView = (InfoView) findViewById(R.id.info_view);

        // Swipe down refresh
        swipeRefreshLayout.setOnRefreshListener(this);
        swipeRefreshLayout.setSwipeableChildren(R.id.catalog_grid);

        // Catalog list
        catalogAdapter = new CatalogAdapter(this);
        catalog = new Catalog();
        catalogList.setAdapter(catalogAdapter);
        catalogList.setOnItemClickListener(catalogItemClickListener);

        // Before anything check if the sensors are available
        boolean checkSensors = sharedPreferences.getBoolean(PREF_CHECKSENS, PREF_CHECKSENS_DEFAULT);
        if (!Utils.sensorsAvailable(this) && checkSensors) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.main_dialog_nosensor_title)
                    .setMessage(R.string.main_dialog_nosensor_message)
                    .setNegativeButton(R.string.common_ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sharedPreferences.edit().putBoolean(PREF_CHECKSENS, false).apply();
                            dialog.dismiss();
                        }
                    })
                    .show();
        }

        infoView.show(R.string.main_info_empty_title, R.string.main_info_empty_message);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register broadcast listener
        registerReceiver(broadcastReceiver, broadcastFilter);

        // Check if refresh is running without service
        if (swipeRefreshLayout.isRefreshing() && !SyncManager.isRunning()) {
            swipeRefreshLayout.setRefreshing(false);
        }

        // Load catalog if available
        loadLocalContent();
        refreshList();

        // Check if catalog is expired
        InternalData internalData = new InternalData(this);
        long lastTimestamp = internalData.readLong(LD_TIMESTAMP, 0);
        long delta = getTimestamp() - lastTimestamp;

        if (delta < 0 || delta > T_CATALOG_EXPIRATION || catalog.size() == 0) {
            // Catalog has expired download
            Log.d(TAG, "Catalog has reached expiration");
            loadRemoteContent();
        } else {
            Log.d(TAG, "Catalog is still valid!");
        }
    }


    @Override
    protected void onPause() {
        super.onPause();

        unregisterReceiver(broadcastReceiver);
    }

    // Download Listener
    @Override
    public void onCompleted(int id, Bundle extra) {
        switch (id) {

            case WallpaperDownloader.ID:
                CatalogItem downloadedItem = extra.getParcelable(WallpaperDownloader.EXTRA_CATALOG_ITEM);
                startSetActivity(downloadedItem);
                refreshList(); // refresh list for downloaded icon
                break;

            case CustomCreator.ID:
                startSetActivity();
                break;
        }
    }

    @Override
    public void onFailed(int id, Bundle bundle) {
        switch (id) {
            case WallpaperDownloader.ID:
                swipeRefreshLayout.setRefreshing(false); // Stop refresh layout
                int errorCode = bundle.getInt(WallpaperDownloader.EXTRA_FAIL_CODE);
                if (errorCode == WallpaperDownloader.FAIL_CODE_TIMEOUT) {
                    showTimeoutError();
                } else {
                    showBackgroundDownloadError(); // Show background download error
                }
                break;

            case CustomCreator.ID:
                showCustomBackgroundError();
                break;
        }
    }

    // Menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);

        menu.findItem(R.id.main_menu_sort).getSubMenu().getItem(0).setChecked(true);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
        switch (item.getItemId()) {

            case R.id.main_menu_refresh:
                // Reload catalog from the server
                loadRemoteContent();
                return true;

            case R.id.main_menu_set:
                // Show live-wallpaper preview
                openLWSetter(context);
                return true;

            case R.id.main_menu_custom:
                // Set custom background
                startPicker();
                return true;

            case R.id.main_menu_settings:
                // Start settings
                Intent intent = new Intent(context, SettingsActivity.class);
                startActivity(intent);
                return true;

            // Sort
            case R.id.main_menu_sort_new:
                // New
                item.setChecked(true);
                catalog.sort(Catalog.SORT_BY_NEW);
                refreshList();
                return true;

            case R.id.main_menu_sort_title:
                // Name
                item.setChecked(true);
                catalog.sort(Catalog.SORT_BY_TITLE);
                refreshList();
                return true;

            case R.id.main_menu_sort_author:
                // Author
                item.setChecked(true);
                catalog.sort(Catalog.SORT_BY_AUTHOR);
                refreshList();
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    // Catalog click listeners
    private final AdapterView.OnItemClickListener catalogItemClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            // Check if background is available offline
            final CatalogItem catalogItem = catalog.get(position);
            if (backgroundExist(catalogItem.getId(), context)) {
                // Background already downloaded
                startSetActivity(catalogItem);
            } else {
                //downloadBackground(item);
                setShowPreview(catalogItem);
            }
        }
    };

    @Override
    public void onRefresh() {
        loadRemoteContent();
    }

    @SuppressLint("NewApi")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // handle result of pick image chooser
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CropImage.PICK_IMAGE_CHOOSER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            this.imageUri = CropImage.getPickImageResultUri(this, data);

            // For API >= 23 we need to check specifically that we have permissions to read external storage.
            if (CropImage.isReadExternalStoragePermissionsRequired(this, imageUri)) {
                // request permissions and handle the result in onRequestPermissionsResult()
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, CropImage.PICK_IMAGE_PERMISSIONS_REQUEST_CODE);
            } else {
                // no permissions required or already grunted, can start crop image activity
                startCropper(imageUri);
            }
        }

        if (requestCode == CropImage.CROP_IMAGE_ACTIVITY_REQUEST_CODE && resultCode == RESULT_OK) {
            CropImage.ActivityResult result = CropImage.getActivityResult(data);
            Uri resultUri = result.getUri();

            // Start creator
            new CustomCreator(this, resultUri).setListener(this).execute();
        }

        if (requestCode == PreviewActivity.PREVIEW_ACTIVITY_REQUEST_CODE && resultCode == PreviewActivity.RESULT_OK) {
            if (data != null) {
                CatalogItem catalogItem = data.getParcelableExtra(PreviewActivity.EXTRA_CATALOG_ITEM);
                if (catalogItem != null) {
                    downloadBackground(catalogItem);
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CropImage.PICK_IMAGE_PERMISSIONS_REQUEST_CODE) {
            if (imageUri != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // required permissions granted, start crop image activity
                startCropper(imageUri);
            }
        }
    }

    // Refresh list and display errors
    private void refreshList() {
        // Check if there's content
        if (catalog != null && catalog.size() == 0) {
            // Display error message
            Log.d(TAG, "Refresh called but catalog is empty!");
            return;
        }

        // Infoview is no longer needed
        infoView.hide();

        // Set list content to list
        catalogAdapter.setContent(catalog);

    }

    private boolean loadLocalContent() {
        return catalog.loadFromCache(context);
    }

    // Load catalog remotely
    private void loadRemoteContent() {

        // Clear cache
        StorageHelper.deleteFolder(getCacheFolder(this));

        if (Utils.checkConnection(this)) {
            // Start the refresh layout
            swipeRefreshLayout.setRefreshing(true);

            // Start the download
            SyncManager.start(context, false);
        } else {
            // Stop refresh layout
            swipeRefreshLayout.setRefreshing(false);

            // Show connection error
            Snackbar.make(rootView, R.string.error_connection, Snackbar.LENGTH_LONG).show();
        }
    }

    // Download background
    private void downloadBackground(final CatalogItem item) {
        // Download
        new WallpaperDownloader(context, item)
                .setListener((MyAsync.MyAsyncInterface) context)
                .execute();
    }

    // Set background id as wallpaper
    private void startSetActivity(CatalogItem catalogItem) {
        // Start set activity
        Intent intent = new Intent(context, SetActivity.class);
        intent.putExtra(SetActivity.EXTRA_IS_CUSTOM, false);
        intent.putExtra(SetActivity.EXTRA_CATALOG_ITEM, catalogItem);
        startActivity(intent);
    }

    // Set background id as wallpaper
    private void startSetActivity() {
        // Start set activity
        Intent intent = new Intent(context, SetActivity.class);
        intent.putExtra(SetActivity.EXTRA_IS_CUSTOM, true);
        startActivity(intent);
    }

    // Start preview activity
    private void setShowPreview(CatalogItem catalogItem) {
        // Start preview activity
        Intent intent = new Intent(context, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_CATALOG_ITEM, catalogItem);
        startActivityForResult(intent, PreviewActivity.PREVIEW_ACTIVITY_REQUEST_CODE);
    }

    // Pick
    private void startPicker() {
        CropImage.startPickImageActivity(this);
    }

    // Crop
    private void startCropper(Uri uri) {
        CropImage.activity(uri).setAspectRatio(1, 1).start(this);
    }

    // Alerts
    private void showCatalogDownloadError() {
        Snackbar
                .make(rootView, R.string.error_catalog, Snackbar.LENGTH_LONG)
                .setAction(R.string.common_retry, new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        loadRemoteContent();
                    }
                })
                .show();
    }

    private void showBackgroundDownloadError() {
        Snackbar.make(rootView, R.string.error_download, Snackbar.LENGTH_LONG).show();
    }

    private void showCustomBackgroundError() {
        Snackbar.make(rootView, R.string.error_customwp, Snackbar.LENGTH_LONG).show();
    }

    private void showTimeoutError() {
        Snackbar.make(rootView, R.string.error_timeout, Snackbar.LENGTH_LONG).show();
    }
}