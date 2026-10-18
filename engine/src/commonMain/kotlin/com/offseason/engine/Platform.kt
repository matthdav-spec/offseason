package com.offseason.engine

/** Zips text files (UTF-8). Each platform provides its own zip code. */
expect fun zipFiles(files: List<Pair<String, String>>): ByteArray
