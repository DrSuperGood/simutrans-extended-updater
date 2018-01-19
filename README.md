# Simutrans Extended Updater
Updater application for Nightly builds of Simutrans Extended written using Java 9.

The full nightly bundle of Simutrans Extended is several hundred megabytes. However, every night only a small subset of that, usually under 20MB, is changed and requires redownloading. This application efficiently detects what files have changed and only downloads what is needed.

There are two applications that are used to achieve this functionality. There is a command line tool run on the server which builds the manifest file of SHA-256 hashes used to detect file changes. Then there is the client which downloads this manifest file from James’s server and compares it with the existing manifest file (if any) to determine file changes. The client then automatically downloads the changed files from James’s server.

The client will not only update existing Simutrans Extended installs, but also can download new clean installs as well. For more information on how to use see the official forums @ https://forum.simutrans.com/ .

Both were written quite quickly and roughly to make Simutrans Extended more accessible for testing. Personal testing has shown the client application to be reasonably reliable, updating successfully over several weeks. Over time code quality may improve.

Vaguely reusable components include the SHA-256 manifest generation system as well as a very crudely written asynchronous URL to file download system. The asynchronous URL to file download system is required for a reasonable download rate as the updater might have to download several thousand very small files.
