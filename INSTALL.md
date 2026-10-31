# Installing Ferry

Ferry sends files and clipboard text/images between a Mac and an Android phone on the same Wi-Fi.
You need **Ferry.app** on the Mac and **Ferry.apk** on the phone.

## Mac (macOS 14 or later, Intel or Apple Silicon)

1. Drag **Ferry.app** into **Applications**.
2. Ferry isn't from the App Store, so macOS blocks it the first time. Open Terminal and run:

   ```
   xattr -dr com.apple.quarantine /Applications/Ferry.app
   ```

   (Or: open Ferry once, then go to System Settings → Privacy & Security and click **Open Anyway**.)
3. Open Ferry. Click **Allow** when macOS asks to find devices on your local network.
4. Ferry opens a window and adds an icon to the menu bar. If the icon is hidden, turn Ferry on in
   System Settings → Menu Bar → Allow in the Menu Bar.
5. Optional: to send from any app's **Share** menu, open Share → **Edit Extensions…** and tick **Ferry**.

## Android phone (Android 10 or later)

1. Copy **Ferry.apk** to the phone (USB, Google Drive, email…) and open it.
2. Allow installing from that app when Android asks ("Install unknown apps"), then tap **Install**.
3. Open Ferry and allow notifications.
4. Tap **Allow background** in Ferry, so the phone doesn't stop Ferry when the screen is off.
   On OPPO/Realme/OnePlus phones also allow background activity in the app's battery settings.

## Pairing

1. Put both on the same Wi-Fi with Ferry open on each.
2. On the phone, tap the Mac's name. Check that the code on both screens matches, then click
   **Allow** on the Mac.

## Sending

| From the Mac | From the phone |
| --- | --- |
| **Send clipboard** in the Ferry window or menu | **Send clipboard** in Ferry, its notification, or the Quick Settings tile |
| **Send files…**, or drop files on the window | **Send files**, or Share → **Ferry** in any app |
| Share → **Ferry** in any app | |

Files arrive in **Downloads** on the Mac and **Download/Ferry** on the phone. Clipboard text and
images go straight onto the other device's clipboard.

## If it doesn't connect

- Both must be on the same Wi-Fi network (not a guest network that isolates devices).
- Ferry must be running on the Mac.
- Transfers aren't encrypted yet, so use Ferry on networks you trust.
