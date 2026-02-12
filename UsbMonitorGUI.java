// USBMonitorGUI.java
import org.usb4java.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Representation of a USB device
class MyDevice {
    private String name;
    private String manufacturer;
    private String deviceId;
    private String deviceType;
    private String serialNumber;
    private String storageCapacity;
// Constructor
    public MyDevice(String name, String manufacturer, String deviceId, String deviceType, String serialNumber, String storageCapacity) {
        this.name = name;
        this.manufacturer = manufacturer;
        this.deviceId = deviceId;
        this.deviceType = deviceType;
        this.serialNumber = serialNumber;
        this.storageCapacity = storageCapacity;
    }
// Getters
    public String getName() { return name; }
    public String getManufacturer() { return manufacturer; }
    public String getDeviceId() { return deviceId; }
    public String getDeviceType() { return deviceType; }
    public String getSerialNumber() { return serialNumber; }
    public String getStorageCapacity() { return storageCapacity; }

    // Format device info as a string
    @Override
    public String toString() {
        return String.format("Device: %s, Manufacturer: %s, ID: %s, Type: %s, Serial: %s, Capacity: %s",
                name, manufacturer, deviceId, deviceType, 
                serialNumber != null ? serialNumber : "N/A", 
                storageCapacity != null ? storageCapacity : "N/A");
    }
}

// Log entry representing a device event
class LogEntry {
    private MyDevice device;
    private String action;
    private LocalDateTime timestamp;

// Constructor
    public LogEntry(MyDevice device, String action) {
        this.device = device;
        this.action = action;
        this.timestamp = LocalDateTime.now();
    }

// Format log entry as a string
    public String formatEntry() {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return "[" + timestamp.format(formatter) + "] USB " + action + " - " + device.toString();
    }
}

// Simple logger to append log entries to a file
class Logger {
    private String logFilePath;

    public Logger(String logFilePath) {
        this.logFilePath = logFilePath;
    }

    public void log(LogEntry entry) {
        try (FileWriter writer = new FileWriter(logFilePath, true)) {
            writer.write(entry.formatEntry() + System.lineSeparator());
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }
}

// USB scanning using WMI
class WmiUsbScanner {
    public static List<USBMonitor.DeviceInfo> getConnectedDevices() {
        List<USBMonitor.DeviceInfo> devices = new ArrayList<>();
        try {
            Process process = Runtime.getRuntime().exec(
                "wmic diskdrive where \"InterfaceType='USB'\" get DeviceID,Model,SerialNumber,Size /format:list"
            );
            Scanner scanner = new Scanner(process.getInputStream());
            String deviceId = null, model = null, serial = null, size = null;

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("DeviceID=")) {
                    deviceId = line.substring(9);
                } else if (line.startsWith("Model=")) {
                    model = line.substring(6);
                } else if (line.startsWith("SerialNumber=")) {
                    serial = line.substring(13);
                } else if (line.startsWith("Size=")) {
                    long bytes = 0;
                    try { 
                        bytes = Long.parseLong(line.substring(5).trim()); 
                    } catch (Exception ignored) {}
                    
                    if (bytes > 0) {
                        long gb = bytes / (1024 * 1024 * 1024);
                        size = gb + " GB";
                    } else {
                        size = "Unknown";
                    }

                    // Once we have all data, add device
                    if (deviceId != null && model != null) {
                        devices.add(new USBMonitor.DeviceInfo(
                            "WMI_" + (serial != null ? serial : model.hashCode()), // Unique ID
                            model,           // Name
                            "Unknown",       // Manufacturer
                            serial,          // Serial number
                            "Mass Storage",  // Type
                            size             // Capacity
                        ));
                    }

                    deviceId = model = serial = size = null; // reset for next
                }
            }
            scanner.close();
        } catch (IOException e) {
            System.err.println("WMI scan error: " + e.getMessage());
        }
        return devices;
    }
}

// USB scanning using libusb
class UsbScanner {
    public static List<USBMonitor.DeviceInfo> getConnectedDevices() {
        List<USBMonitor.DeviceInfo> devices = new ArrayList<>();
        if (USBMonitor.context == null) return devices;

        try {
            DeviceList list = new DeviceList();
            int cnt = LibUsb.getDeviceList(USBMonitor.context, list);
            if (cnt >= 0) {
                try {
                    for (Device usbDevice : list) {
                        DeviceDescriptor desc = new DeviceDescriptor();
                        if (LibUsb.getDeviceDescriptor(usbDevice, desc) != LibUsb.SUCCESS) continue;

                        String manufacturer = getStringDescriptor(usbDevice, desc.iManufacturer());
                        String product = getStringDescriptor(usbDevice, desc.iProduct());
                        String deviceType = determineDeviceType(desc);
                        String deviceId = String.format("%04X:%04X", desc.idVendor() & 0xFFFF, desc.idProduct() & 0xFFFF);
                        String serialNumber = getStringDescriptor(usbDevice, desc.iSerialNumber());

                        String displayName = product != null && !product.equals("Unknown") ? product : "USB Device";
                        String displayManufacturer = manufacturer != null && !manufacturer.equals("Unknown") ? manufacturer : "Unknown Manufacturer";
                        
                        devices.add(new USBMonitor.DeviceInfo(
                            deviceId,
                            displayName,
                            displayManufacturer,
                            serialNumber,
                            deviceType,
                            null // No capacity from libusb
                        ));
                    }
                } finally {
                    LibUsb.freeDeviceList(list, true);
                }
            }
        } catch (Exception e) {
            System.err.println("Error getting libusb devices: " + e.getMessage());
        }
        return devices;
    }

    // Helper to get string descriptor
    private static String getStringDescriptor(Device device, byte index) {
        if (index == 0) return "Unknown";

        DeviceHandle handle = new DeviceHandle();
        int openResult = LibUsb.open(device, handle);
        if (openResult != LibUsb.SUCCESS) {
            return "Unknown";
        }

        try {
            StringBuffer buffer = new StringBuffer(256);
            int ret = LibUsb.getStringDescriptorAscii(handle, index, buffer);
            if (ret < 0) return "Unknown";
            return buffer.toString();
        } catch (Exception e) {
            return "Unknown";
        } finally {
            LibUsb.close(handle);
        }
    }

// Heuristic to determine device type from class code
    private static String determineDeviceType(DeviceDescriptor desc) {
        int deviceClass = desc.bDeviceClass() & 0xFF;
        switch (deviceClass) {
            case 0x00: return "Composite Device";
            case 0x03: return "Human Interface Device (HID)";
            case 0x08: return "Mass Storage";
            case 0x09: return "USB Hub";
            case 0x07: return "Printer";
            case 0x0E: return "Video Device";
            case 0x0A: return "CDC Data";
            default: return "USB Device";
        }
    }
}

// Main USB monitoring class
class USBMonitor {
    static Context context;
    private static final List<USBEventListener> listeners = new CopyOnWriteArrayList<>();
    private static volatile boolean libusbInitialized = false;
    private static volatile boolean wmiInitialized = true;
    private static Thread pollThread;
    private static volatile boolean running = false;

    private static Map<String, DeviceInfo> mergedDevices = new ConcurrentHashMap<>();
    private static Set<String> notifiedDevices = new ConcurrentHashMap().newKeySet();
    
// Initialize USB monitoring
    public static synchronized void initialize() {
        if (libusbInitialized && wmiInitialized) return;

        try {
            context = new Context();
            int result = LibUsb.init(context);
            if (result == LibUsb.SUCCESS) {
                libusbInitialized = true;
                System.out.println("Libusb initialized successfully");
            } else {
                System.err.println("Unable to initialize libusb: " + LibUsb.strError(result));
            }
        } catch (Exception e) {
            System.err.println("Libusb initialization failed: " + e.getMessage());
        }

        wmiInitialized = true;
        running = true;

        pollThread = new Thread(() -> {
            System.out.println("USB Monitor: Starting device polling thread");

            scanAndMergeDevices();

            Map<String, DeviceInfo> previousDevices = new HashMap<>(mergedDevices);

            while (running) {
                try {
                    Thread.sleep(3000);

                    scanAndMergeDevices();

                    for (String deviceId : mergedDevices.keySet()) {
                        if (!previousDevices.containsKey(deviceId) && !notifiedDevices.contains(deviceId)) {
                            DeviceInfo deviceInfo = mergedDevices.get(deviceId);
                            if (deviceInfo != null) {
                                notifyDeviceConnected(deviceInfo);
                                notifiedDevices.add(deviceId);
                            }
                        }
                    }

                    for (String deviceId : previousDevices.keySet()) {
                        if (!mergedDevices.containsKey(deviceId)) {
                            DeviceInfo removedDevice = previousDevices.get(deviceId);
                            notifyDeviceDisconnected(removedDevice);
                            notifiedDevices.remove(deviceId);
                        }
                    }

                    previousDevices = new HashMap<>(mergedDevices);

                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    System.err.println("Error in USB polling: " + e.getMessage());
                }
            }
            System.out.println("USB Monitor: Polling thread stopped");
        }, "USB-Polling-Thread");

        pollThread.setDaemon(true);
        pollThread.start();
        System.out.println("USB Monitor initialized successfully");
    }

// Merge devices from libusb and WMI
private static void scanAndMergeDevices() {
    Map<String, DeviceInfo> newMergedDevices = new HashMap<>();

    List<DeviceInfo> libusbDevices = UsbScanner.getConnectedDevices();
    List<DeviceInfo> wmiDevices = WmiUsbScanner.getConnectedDevices();

   //System.out.println("Libusb found: " + libusbDevices.size() + " devices");
  //  System.out.println("WMI found: " + wmiDevices.size() + " devices");

//adding WMI devices first
for (DeviceInfo wmiDev : wmiDevices) {
    String baseId = wmiDev.getDeviceId() != null ? wmiDev.getDeviceId() : wmiDev.getProductName();
    if (baseId == null || baseId.isEmpty()) baseId = "Unknown";
    newMergedDevices.put("WMI_" + baseId, wmiDev);
}


//adding libusb devices, merging details if possible
    for (DeviceInfo libDev : libusbDevices) {
        newMergedDevices.put(libDev.getDeviceId(), libDev);
    }

    mergedDevices.clear();
    mergedDevices.putAll(newMergedDevices);
}


// Extract VID:PID from device ID string
private static String extractVidPid(String deviceId) {
    if (deviceId == null) return null;
    deviceId = deviceId.toUpperCase();

    int vidIndex = deviceId.indexOf("VID_");
    int pidIndex = deviceId.indexOf("PID_");

    if (vidIndex >= 0 && pidIndex >= 0 
        && deviceId.length() >= vidIndex + 8 
        && deviceId.length() >= pidIndex + 8) {

        String vid = deviceId.substring(vidIndex + 4, vidIndex + 8);
        String pid = deviceId.substring(pidIndex + 4, pidIndex + 8);
        return vid + ":" + pid;
    }
    return null; // VID:PID not found
}



// Notify listeners of device connection
    private static void notifyDeviceConnected(DeviceInfo deviceInfo) {
        SwingUtilities.invokeLater(() -> {
            for (USBEventListener listener : listeners) {
                listener.onDeviceConnected(deviceInfo);
            }
        });
    }

// Notify listeners of device disconnection
    private static void notifyDeviceDisconnected(DeviceInfo deviceInfo) {
        SwingUtilities.invokeLater(() -> {
            for (USBEventListener listener : listeners) {
                listener.onDeviceDisconnected(deviceInfo);
            }
        });
    }

// Event listener interface
    public interface USBEventListener {
        void onDeviceConnected(DeviceInfo device);
        void onDeviceDisconnected(DeviceInfo device);
    }

    // Add event listener
    public static void addEventListener(USBEventListener listener) {
        listeners.add(listener);
    }

    // Remove event listener
    public static void removeEventListener(USBEventListener listener) {
        listeners.remove(listener);
    }

// Get current merged device list
    public static List<DeviceInfo> getConnectedDevices() {
        return new ArrayList<>(mergedDevices.values());
    }

// DeviceInfo class to hold device details
    public static class DeviceInfo {
        private String deviceId, productName, manufacturer, serialNumber, deviceType, storageCapacity;

        public DeviceInfo(String deviceId, String productName, String manufacturer,
                          String serialNumber, String deviceType, String storageCapacity) {
            this.deviceId = deviceId;
            this.productName = productName != null ? productName : "Unknown Device";
            this.manufacturer = manufacturer != null ? manufacturer : "Unknown Manufacturer";
            this.serialNumber = serialNumber;
            this.deviceType = deviceType;
            this.storageCapacity = storageCapacity;
        }

// Getters
        public String getDeviceId() { return deviceId; }
        public String getProductName() { return productName; }
        public String getManufacturer() { return manufacturer; }
        public String getSerialNumber() { return serialNumber; }
        public String getDeviceType() { return deviceType; }
        public String getStorageCapacity() { return storageCapacity; }

// Convert to MyDevice for logging
        public MyDevice toMyDevice() {
            return new MyDevice(productName, manufacturer, deviceId, deviceType, serialNumber, storageCapacity);
        }

// Heuristic to determine if device is an input device
        public boolean isInputDevice() {
            String lowerProduct = productName.toLowerCase();
            String lowerType = deviceType.toLowerCase();
            
            if (lowerType.contains("hid") || lowerType.contains("human interface") || lowerType.contains("input")) {
                return true;
            }
            
            if (lowerProduct.contains("keyboard") || lowerProduct.contains("mouse") ||
                lowerProduct.contains("controller") || lowerProduct.contains("gamepad") ||
                lowerProduct.contains("joystick") || lowerProduct.contains("touchpad") ||
                lowerProduct.contains("webcam") || lowerProduct.contains("camera") ||
                lowerProduct.contains("microphone")) {
                return true;
            }
            
            return false;
        }
// Detailed string for logging
        @Override
public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("Detected: Device connected - ")
      .append(productName != null ? productName : "Unknown Device")
      .append(" - Type: ").append(deviceType != null ? deviceType : "Unknown")
      .append(", Manufacturer: ").append(manufacturer != null ? manufacturer : "Unknown")
      .append(", Serial: ").append(serialNumber != null ? serialNumber : "Unknown")
      .append(", Capacity: ").append(storageCapacity != null ? storageCapacity : "N/A")
      .append("\nID: ").append(deviceId != null ? deviceId : "N/A");
    return sb.toString();
}

// Formatted string for display in combo box
        public String toDisplayString() {
            return String.format("%s (%s) - %s [%s]",
                    productName, deviceId, manufacturer, deviceType);
        }
// Formatted string for logging
        public String toLogString() {
            StringBuilder sb = new StringBuilder();
            sb.append(productName).append(" - Type: ").append(deviceType)
              .append(", ID: ").append(deviceId)
              .append(", Manufacturer: ").append(manufacturer);
            
            if (serialNumber != null && !serialNumber.equals("Unknown")) {
                sb.append(", Serial: ").append(serialNumber);
            }
            if (storageCapacity != null) {
                sb.append(", Capacity: ").append(storageCapacity);
            }
            
            return sb.toString();
        }
    }
// Cleanup resources on exit
    public static void cleanup() {
        running = false;
        if (pollThread != null && pollThread.isAlive()) {
            pollThread.interrupt();
            try {
                pollThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (context != null) {
            LibUsb.exit(context);
            context = null;
        }
        libusbInitialized = false;
        wmiInitialized = false;
        mergedDevices.clear();
        notifiedDevices.clear();
        System.out.println("USB Monitor cleaned up");
    }

// Main method to launch the GUI
    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        SwingUtilities.invokeLater(() -> {
            UsbMonitorGUI gui = new UsbMonitorGUI();
            gui.setLocationRelativeTo(null);
            gui.setVisible(true);
        });
    }
}

// Main GUI class
public class UsbMonitorGUI extends JFrame {
    private JTextArea logArea;
    private Logger logger;
    private List<LogEntry> logEntries;
    private JComboBox<String> deviceComboBox;
    private List<USBMonitor.DeviceInfo> currentDevices;
    private JTextField manualDeviceField, manualManufacturerField;
    private boolean autoMonitoring = false;
    private JButton autoMonitorBtn;
    private JCheckBox showOnlyInputDevicesCheckbox;
    private boolean usbInitialized = false;
// Constructor to set up the GUI
    public UsbMonitorGUI() {
        logger = new Logger("usb_log.txt");
        logEntries = new ArrayList<>();
        currentDevices = new ArrayList<>();

        setTitle("USB Sentinel: Real-time Device Monitor");
        setSize(900, 700);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        // Manual panel
        JPanel manualPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        manualPanel.setBorder(BorderFactory.createTitledBorder("Manual Device Entry"));
        manualPanel.add(new JLabel("Device Name:"));
        manualDeviceField = new JTextField();
        manualPanel.add(manualDeviceField);
        manualPanel.add(new JLabel("Manufacturer:"));
        manualManufacturerField = new JTextField();
        manualPanel.add(manualManufacturerField);

        // Devices panel with filter
        deviceComboBox = new JComboBox<>();
        showOnlyInputDevicesCheckbox = new JCheckBox("Show Only Input Devices");

        JPanel devicesPanel = new JPanel(new BorderLayout(5, 5));
        devicesPanel.setBorder(BorderFactory.createTitledBorder("Connected USB Devices"));

        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        filterPanel.add(showOnlyInputDevicesCheckbox);

        devicesPanel.add(filterPanel, BorderLayout.NORTH);
        devicesPanel.add(new JScrollPane(deviceComboBox), BorderLayout.CENTER);

        JButton refreshBtn = new JButton("Refresh Devices");
        autoMonitorBtn = new JButton("Start Auto Monitoring");
        JButton detectInputBtn = new JButton("Detect Input Devices");
        JButton testDetectionBtn = new JButton("Test Detection");

        JPanel deviceButtonPanel = new JPanel(new FlowLayout());
        deviceButtonPanel.add(refreshBtn);
        deviceButtonPanel.add(autoMonitorBtn);
        deviceButtonPanel.add(detectInputBtn);
        deviceButtonPanel.add(testDetectionBtn);
        devicesPanel.add(deviceButtonPanel, BorderLayout.SOUTH);

        // Log panel
        logArea = new JTextArea(15, 80);
        logArea.setEditable(false);
        logArea.setBackground(new Color(30, 30, 30));
        logArea.setForeground(Color.WHITE);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));

        JPanel logPanel = new JPanel(new BorderLayout(5, 5));
        logPanel.setBorder(BorderFactory.createTitledBorder("Activity Log"));
        logPanel.add(new JScrollPane(logArea), BorderLayout.CENTER);

        // Action buttons
        JPanel actionPanel = new JPanel(new FlowLayout());
        JButton manualInsertBtn = new JButton("Log Manual Insertion");
        JButton manualRemoveBtn = new JButton("Log Manual Removal");
        JButton clearBtn = new JButton("Clear Log");
        actionPanel.add(manualInsertBtn);
        actionPanel.add(manualRemoveBtn);
        actionPanel.add(clearBtn);

        // Layout
        JPanel topContainer = new JPanel(new BorderLayout(10, 10));
        topContainer.add(manualPanel, BorderLayout.CENTER);
        //topContainer.add(devicesPanel, BorderLayout.EAST);
        JPanel centerContainer = new JPanel(new BorderLayout(10, 10));
        centerContainer.add(topContainer, BorderLayout.NORTH);
        centerContainer.add(devicesPanel, BorderLayout.CENTER);
        
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        mainPanel.add(centerContainer, BorderLayout.NORTH);
        mainPanel.add(logPanel, BorderLayout.CENTER);
        mainPanel.add(actionPanel, BorderLayout.SOUTH);
        add(mainPanel);

        // Button actions
        refreshBtn.addActionListener(e -> refreshDeviceList());
        autoMonitorBtn.addActionListener(e -> toggleAutoMonitoring());
        detectInputBtn.addActionListener(e -> detectInputDevices());
        testDetectionBtn.addActionListener(e -> testUSBDetection());
        showOnlyInputDevicesCheckbox.addActionListener(e -> refreshDeviceList());
        manualInsertBtn.addActionListener(e -> logManualDevice("Inserted"));
        manualRemoveBtn.addActionListener(e -> logManualDevice("Removed"));
        clearBtn.addActionListener(e -> logArea.setText(""));
        // Initialize USB monitoring
        initializeUSBMonitoring();
        cleanupOnExit();
        refreshDeviceList();
        // Initial log messages
        addToLogArea("USB Monitor started successfully");
        addToLogArea("Click 'Refresh Devices' to scan for connected USB devices");
    }
//  Initialize USB monitoring and event listeners
    private void initializeUSBMonitoring() {
        new Thread(() -> {
            try {
                USBMonitor.initialize();
                usbInitialized = true;
                
                USBMonitor.addEventListener(new USBMonitor.USBEventListener() {
                    public void onDeviceConnected(USBMonitor.DeviceInfo device) {
                        SwingUtilities.invokeLater(() -> {
                            if (autoMonitoring) logAutoEvent(device, "Inserted");
                            refreshDeviceList();
                            addToLogArea("Detected: Device connected - " + device.toLogString());

                            if (device.isInputDevice()) {
                                addToLogArea(" Input device connection: " + device.getProductName());
                            }
                        });
                    }
// Handle device disconnection
                    public void onDeviceDisconnected(USBMonitor.DeviceInfo device) {
                        SwingUtilities.invokeLater(() -> {
                            if (autoMonitoring) logAutoEvent(device, "Removed");
                            refreshDeviceList();
                            addToLogArea("Detected: Device disconnected - " + device.toLogString());

                            if (device.isInputDevice()) {
                                addToLogArea(" Input device detected: " + device.getProductName());
                            }
                        });
                    }
                });

                SwingUtilities.invokeLater(() -> {
                    addToLogArea("USB Monitoring initialized successfully");
                    addToLogArea("Using combined detection: Windows Management Instrumentation + Libusb");
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    addToLogArea("Error initializing USB monitoring: " + e.getMessage());
                });
            }
        }).start();
    }
// Cleanup resources on exit
    private void cleanupOnExit() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                USBMonitor.cleanup();
                System.exit(0);
            }
        });
    }
// Refresh device list with filtering
    private void refreshDeviceList() {
        SwingUtilities.invokeLater(() -> {
            try {
                if (!usbInitialized) {
                    deviceComboBox.removeAllItems();
                    deviceComboBox.addItem("USB Monitoring initializing...");
                    return;
                }

                List<USBMonitor.DeviceInfo> devices = USBMonitor.getConnectedDevices();
                currentDevices.clear();

                boolean filterInput = showOnlyInputDevicesCheckbox.isSelected();
                int totalDevices = devices.size();
                int displayedDevices = 0;
                int inputDevices = 0;

                for (USBMonitor.DeviceInfo device : devices) {
                    if (device.isInputDevice()) {
                        inputDevices++;
                    }
                }

                for (USBMonitor.DeviceInfo device : devices) {
                    if (!filterInput || device.isInputDevice()) {
                        currentDevices.add(device);
                        displayedDevices++;
                    }
                }

                DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>();
                
                if (currentDevices.isEmpty()) {
                    if (filterInput) {
                        model.addElement("No input devices found (" + totalDevices + " total devices)");
                    } else {
                        model.addElement("No USB devices detected");
                    }
                } else {
                   for (USBMonitor.DeviceInfo d : currentDevices) {
                         String displayText = d.toDisplayString();
                         model.addElement(displayText);
                        }
                }
                
                deviceComboBox.setModel(model);
                deviceComboBox.setToolTipText(String.format(
                    "Showing %d of %d total devices (%d input devices)", 
                    displayedDevices, totalDevices, inputDevices
                ));

            } catch (Exception e) {
                addToLogArea("Error refreshing device list: " + e.getMessage());
            }
        });
    }
// Detectecting  and logging input devices
    private void detectInputDevices() {
        if (!usbInitialized) {
            addToLogArea("Error: USB monitoring not initialized yet. Please wait...");
            return;
        }

        new Thread(() -> {
            try {
                List<USBMonitor.DeviceInfo> devices = USBMonitor.getConnectedDevices();
                final List<USBMonitor.DeviceInfo> inputDevices = new ArrayList<>();
                
                for (USBMonitor.DeviceInfo device : devices) {
                    if (device.isInputDevice()) {
                        inputDevices.add(device);
                    }
                }
                
                SwingUtilities.invokeLater(() -> {
                    addToLogArea(" Scanning for input devices...");
                    addToLogArea("Total USB devices found: " + devices.size());
                    
                    if (inputDevices.isEmpty()) {
                        addToLogArea("No input devices detected!");
                    } else {
                        addToLogArea("Input device found: " + inputDevices.size());
                        for (USBMonitor.DeviceInfo device : inputDevices) {
                            addToLogArea(device.toLogString());
                        }
                    }
                    addToLogArea(" Scan complete.");
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    addToLogArea("Error detecting input devices: " + e.getMessage());
                });
            }
        }).start();
    }
// Test USB detection functionality
    private void testUSBDetection() {
        addToLogArea(" Testing USB detection...");
        if (!usbInitialized) {
            addToLogArea("Test failed: USB monitoring not initialized");
            return;
        }
        
        try {
            List<USBMonitor.DeviceInfo> devices = USBMonitor.getConnectedDevices();
            addToLogArea("Test Passed: Found " + devices.size() + " USB device(s)");
            
            for (USBMonitor.DeviceInfo device : devices) {
                addToLogArea("  - " + device.toLogString());
            }
            
            if (devices.isEmpty()) {
                addToLogArea("No devices found - make sure USB devices are connected");
            }
        } catch (Exception e) {
            addToLogArea("Test failed: " + e.getMessage());
        }
    }
// Toggle auto monitoring state
    private void toggleAutoMonitoring() {
        if (!usbInitialized) {
            addToLogArea("Error: USB monitoring not initialized yet");
            return;
        }
        
        autoMonitoring = !autoMonitoring;
        autoMonitorBtn.setText(autoMonitoring ? "Stop Auto Monitoring" : "Start Auto Monitoring");
        addToLogArea(autoMonitoring ? "Auto monitoring STARTED" : "Auto monitoring STOPPED");
    }
// Log manual device actions
    private void logManualDevice(String action) {
        String name = manualDeviceField.getText().trim();
        String manufacturer = manualManufacturerField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter a device name", "Input Required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        MyDevice dev = new MyDevice(name, manufacturer.isEmpty() ? "Unknown" : manufacturer, "Manual", "Manual Entry", null, null);
        LogEntry entry = new LogEntry(dev, action);
        logEntries.add(entry);
        logger.log(entry);
        addToLogArea("Manual: " + entry.formatEntry());
    }
// Log automatic events
    private void logAutoEvent(USBMonitor.DeviceInfo deviceInfo, String action) {
        MyDevice dev = deviceInfo.toMyDevice();
        LogEntry entry = new LogEntry(dev, action);
        logEntries.add(entry);
        logger.log(entry);
    }
// Utility to append text to log area safely from any thread
    private void addToLogArea(String text) {
        SwingUtilities.invokeLater(() -> {
            logArea.append(text + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }}