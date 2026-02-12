# USB Monitor (Java)

## Overview
USB Monitor  is a Java desktop application that detects connected USB storage devices, displays their information in a graphical interface, and logs connection or removal events to a file.

This project demonstrates:
- Real-time hardware monitoring
- File logging
- Java Swing GUI development

## Features
- Scans for connected USB storage devices
- Displays:
  - Device name  
  - Manufacturer  
  - Device ID  
  - Serial number  
  - Storage capacity  
- Logs every USB connection/disconnection with a timestamp
- Real-time GUI updates
- Thread-safe monitoring for stability

## Technologies Used
- **Java**
- **Java Swing** (GUI)
- **usb4java** (USB interaction support)
- **Windows WMI commands** (USB detection)
- **Java file I/O** (logging)

## Project Structure
- **MyDevice** → Represents a USB device and its properties  
- **LogEntry** → Represents a timestamped USB event  
- **Logger** → Writes events to a log file  
- **WmiUsbScanner** → Detects connected USB storage devices  
- **USBMonitorGUI** → Main graphical interface and controller  

## Requirements
- Java **JDK 8 or later**
- **Windows OS** (WMI-based detection)
- usb4java libraries in the classpath (if used)

## How to Run
1. Compile the Java file:
   ```bash
   javac UsbMonitorGUI.java
