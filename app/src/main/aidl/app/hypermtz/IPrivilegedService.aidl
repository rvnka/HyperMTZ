// IPrivilegedService runs inside the Shizuku server process (:shizuku) with
// elevated ADB-shell or root privileges depending on how Shizuku was started.
//
// Transaction code 16777115 is the Shizuku-reserved slot for the destroy
// callback. It must not be removed or renumbered.
package app.hypermtz;

interface IPrivilegedService {

    // Returns a newline-separated listing of a directory's children.
    // Each line is formatted as "name : DIR" or "name : FILE".
    String listDirectory(String path) = 1;

    // Copies sourcePath to destinationPath, creating missing parent directories.
    boolean copyFile(String sourcePath, String destinationPath) = 2;

    // Deletes the file or empty directory at path.
    boolean deleteFile(String path) = 3;

    // Returns the UTF-8 text content of a file, or null on error.
    String readFile(String path) = 4;

    // Creates an empty file at path, including any missing parent directories.
    boolean createFile(String path) = 5;

    // Writes a UTF-8 string to path, overwriting any existing content.
    boolean writeFile(String content, String path) = 6;

    // Decodes the image at sourcePath and saves it as a lossless PNG to destPath.
    // Avoids passing a Bitmap object over Binder (TransactionTooLargeException risk).
    boolean saveBitmapFromFile(String sourcePath, String destPath) = 7;

    // Runs command[] and returns true if the process exits with code 0.
    boolean execute(in String[] command) = 8;

    // Runs command[] and returns its stdout (or stderr if returnError is true).
    // maxLines caps the number of captured lines. timeoutMs is the deadline.
    String executeWithOutput(int maxLines, long timeoutMs, boolean returnError, in String[] command) = 9;

    // Enables an accessibility service by appending componentName
    // ("package/ClassName") to the secure setting.
    boolean enableAccessibilityService(String componentName) = 10;

    // Shizuku reserved: called before a newer version of this service is bound.
    // Must clean up resources and call System.exit() before returning.
    void destroy() = 16777115;
}
