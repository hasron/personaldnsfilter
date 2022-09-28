package dnsfilter.dnsserverconfig;

import android.content.Context;
import android.os.Bundle;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.StringTokenizer;

import dnsfilter.ConfigUtil;
import dnsfilter.ConfigurationAccess;
import dnsfilter.dnsserverconfig.widget.DNSListAdapter;
import dnsfilter.dnsserverconfig.widget.DNSServerConfigEntry;
import dnsfilter.dnsserverconfig.widget.DNSServerConfigEntrySerializer;
import dnsfilter.dnsserverconfig.widget.NotDeserializableException;
import util.ExecutionEnvironment;

public class DNSServerConfigPresenterImpl implements DNSServerConfigPresenter {

    private static final String LINE_SEPARATOR;

    static {
        if (System.getProperty("line.separator") == null) {
            LINE_SEPARATOR = "\n";
        } else {
            LINE_SEPARATOR = System.getProperty("line.separator");
        }
    }

    private static final String DETECT_DNS_PROPERTY_NAME = "detectDNS";
    private static final String FALLBACK_DNS_PROPERTY_NAME = "fallbackDNS";
    private static final String ASSETS_FILE_NAME = "dnsfilter.conf";

    private static final String SAVE_STATE_DETECT_DNS = "detectDNS";
    private static final String SAVE_STATE_DNS_LIST = "fallbackDNS";
    private static final String SAVE_STATE_IS_RAW_MODE = "isRadModeDNS";

    private final ConfigurationAccess CONFIG = ConfigurationAccess.getLocal();
    private final DNSServerConfigEntrySerializer serializer = new DNSServerConfigEntrySerializer();
    private final DNSServerConfigView view;
    private final DNSListAdapter listAdapter;
    private boolean isManualDNSServers = false;

    @Override
    public DNSListAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    public boolean getIsManualDNSServers() {
        return isManualDNSServers;
    }

    DNSServerConfigPresenterImpl(DNSServerConfigView view, Context context, Bundle savedInstanceState) {
        this.view = view;

        List<DNSServerConfigEntry> entries = new ArrayList<>();

        if (savedInstanceState != null) {
            isManualDNSServers = savedInstanceState.getBoolean(SAVE_STATE_DETECT_DNS);
            if (savedInstanceState.getBoolean(SAVE_STATE_IS_RAW_MODE)) {
                view.showRawMode(savedInstanceState.getString(SAVE_STATE_DNS_LIST));
            } else {
                entries = readDNSServerConfigFrom(savedInstanceState.getString(SAVE_STATE_DNS_LIST));
            }
        } else {
            ConfigUtil config = getConfig();
            if (config != null) {
                entries = readDNSServerConfigFrom(
                        DNSServerConfigUtils.formatSerializedProperties(config.getProperties().getProperty(FALLBACK_DNS_PROPERTY_NAME, ""))
                );
                this.isManualDNSServers = !Boolean.parseBoolean(config.getProperties().getProperty(DETECT_DNS_PROPERTY_NAME, "true"));
            }
        }
        this.listAdapter = new DNSListAdapter(context, entries);
    }

    private List<DNSServerConfigEntry> readDNSServerConfigFrom(String source) {
        List<DNSServerConfigEntry> entries = new ArrayList<>();
        ConfigUtil config = getConfig();
        if (config != null) {
            assert LINE_SEPARATOR != null;
            String[] dnsEntries = source.split(LINE_SEPARATOR);
            for (String entry : dnsEntries) {
                if (!entry.isEmpty()) {
                    entries.add(serializer.deserializeSafe(entry));
                }
            }
        }
        return entries;
    }

    private ConfigUtil getConfig() {
        try {
            return CONFIG.getConfigUtil();
        } catch (Exception e) {
            view.showToastAndCloseScreen("Critical error - can't load config. Try to restart application.");
            return null;
        }
    }

    @Override
    public void resetDNSConfigToDefault() {
        try {
            Properties defaultProperties = readDefaultDNSConfig();
            List<DNSServerConfigEntry> entries = readDNSServerConfigFrom(
                    DNSServerConfigUtils.formatSerializedProperties(defaultProperties.getProperty(FALLBACK_DNS_PROPERTY_NAME, ""))
            );
            view.resetToDefaultMode();
            listAdapter.clear();
            listAdapter.addAll(entries);
            view.showToast("DNS configuration is is reset to default");

            isManualDNSServers = !Boolean.parseBoolean(defaultProperties.getProperty(DETECT_DNS_PROPERTY_NAME, "true"));
            view.setManualDNSServers(isManualDNSServers);
        } catch (Exception e) {
            view.showToast(e.getMessage());
        }
    }

    @Override
    public void onChangedEditModeValue(boolean isRawMode, String rawModeTextValue) {
        if (isRawMode) {
            StringBuilder entriesBuilder = new StringBuilder();
            for (int i = 0; i <= listAdapter.getObjectsCount() - 1; i++) {
                entriesBuilder.append(listAdapter.getItem(i).toString()).append(LINE_SEPARATOR);
            }
            view.showRawMode(entriesBuilder.toString());
        } else {
            ArrayList<DNSServerConfigEntry> entries = new ArrayList<>();
            if (rawEntriesToDNSServerEntries(rawModeTextValue, entries)) {
                listAdapter.clear();
                listAdapter.addAll(entries);
                view.resetToDefaultMode();
            }
        }
    }

    @Override
    public void onChangedManualDNSServers(boolean isManualDNSServers) {
        this.isManualDNSServers = isManualDNSServers;
    }

    @Override
    public void applyNewConfiguration(boolean isRawMode, String rawModeTextValue) {
        if (isRawMode && !rawEntriesToDNSServerEntries(rawModeTextValue, null)) {
            view.showToast("Raw text is not possibly to convert");
            return;
        }
        ConfigUtil config = getConfig();
        if (config != null) {
            config.updateConfigValue(FALLBACK_DNS_PROPERTY_NAME, getDNSServerEntriesAsConfig(isRawMode, rawModeTextValue));
            config.updateConfigValue(DETECT_DNS_PROPERTY_NAME, Boolean.toString(!isManualDNSServers));
        }
        view.showToastAndCloseScreen(null);
    }

    @Override
    public void saveState(Bundle outState, boolean isRawMode, String rawModeTextValue) {
        outState.putBoolean(SAVE_STATE_DETECT_DNS, isManualDNSServers);
        if (isRawMode) {
            outState.putString(SAVE_STATE_DNS_LIST, rawModeTextValue);
            outState.putBoolean(SAVE_STATE_IS_RAW_MODE, true);
        } else {
            outState.putString(SAVE_STATE_DNS_LIST, DNSServerEntriesToRawEntries());
            outState.putBoolean(SAVE_STATE_IS_RAW_MODE, false);
        }
    }

    private Properties readDefaultDNSConfig() throws IOException {
        InputStream defIn = ExecutionEnvironment.getEnvironment().getAsset(ASSETS_FILE_NAME);
        Properties defaults = new Properties();
        defaults.load(defIn);
        defIn.close();
        return defaults;
    }

    private String getDNSServerEntriesAsConfig(boolean isRawMode, String rawModeTextValue) {
        String uiText;
        if (isRawMode) {
            uiText = rawModeTextValue;
        } else {
            uiText = DNSServerEntriesToRawEntries();
        }
        String result = "";
        StringTokenizer entries = new StringTokenizer(uiText, "\n");
        while (entries.hasMoreTokens()) {
            String entry = entries.nextToken().trim();
            if (!entry.equals(""))
                result = result + entry + "; ";
        }
        if (!result.equals(""))
            result = result.substring(0, result.length() - 2); // cut last seperator;

        return result;
    }

    private String DNSServerEntriesToRawEntries() {
        StringBuilder entriesBuilder = new StringBuilder();
        for (int i = 0; i <= listAdapter.getObjectsCount() - 1; i++) {
            entriesBuilder.append(listAdapter.getItem(i).toString());
            entriesBuilder.append(LINE_SEPARATOR);
        }

        return entriesBuilder.toString();
    }

    private boolean rawEntriesToDNSServerEntries(String source, ArrayList<DNSServerConfigEntry> entries) {
        DNSServerConfigEntrySerializer serializer = new DNSServerConfigEntrySerializer();
        String[] dnsEntries = source.split(LINE_SEPARATOR);
        try {
            for (String entry : dnsEntries) {
                if (!entry.isEmpty()) {
                    DNSServerConfigEntry deserializedValue = serializer.deserialize(entry);
                    if (entries != null) {
                        entries.add(deserializedValue);
                    }
                }
            }
            return true;
        } catch (NotDeserializableException e) {
            view.showRawModeError(e.getMessage());
            return false;
        }
    }
}

interface DNSServerConfigPresenter {
    void resetDNSConfigToDefault();

    DNSListAdapter getListAdapter();

    boolean getIsManualDNSServers();

    void onChangedEditModeValue(boolean isRawMode, String rawModeTextValue);

    void onChangedManualDNSServers(boolean isManualDNSServers);

    void applyNewConfiguration(boolean isRawMode, String rawModeTextValue);

    void saveState(Bundle outState, boolean isRawNode, String rawModeTextValue);
}