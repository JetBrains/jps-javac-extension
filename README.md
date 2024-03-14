# jps-javac-extension [![official JetBrains project](http://jb.gg/badges/official.svg)](https://github.com/JetBrains/.github/blob/main/profile/README.md)

This project contains infrastructure classes for obtaining data directly from javac's AST.
These are used to collect references to java imports, classes, methods and fields (including compile-time constants) to facilitate
JPS incremental compilation and building ".class"-based references index.
The code is deliberately compiled using legacy java 6 and java 8 platforms to be able to be functional on all platforms starting from java 6.

Originally sources were extracted from
- community/jps/jps-builders-6
- community/jps/java-ref-scanner-8

The resulting jar artifact is not meant to be used directly, but rather as a library artifact in JetBrains IntelliJ platform code.