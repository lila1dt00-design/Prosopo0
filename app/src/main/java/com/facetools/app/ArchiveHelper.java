package com.facetools.app;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts RAR and 7z archives for the Python converter (called from containers.py through
 * Chaquopy). Returns "OK:count:bytes", "ERR:LIMIT", "UNSUPPORTED" or "ERR:message".
 * Any failure (including missing classes on very old Android) is reported as a string, never thrown.
 */
public final class ArchiveHelper {
    private ArchiveHelper() {
    }

    private static final class LimitException extends IOException {
    }

    private static final class State {
        final File root;
        final String rootPath;
        final String prefix;
        final long max;
        final Set<String> blocked;
        int count = 0;
        long total = 0;

        State(File root, String prefix, long max, Set<String> blocked) throws IOException {
            this.root = root;
            this.rootPath = root.getCanonicalPath() + File.separator;
            this.prefix = prefix;
            this.max = max;
            this.blocked = blocked;
        }

        /** Returns the destination file, or null when the entry must be skipped. */
        File target(String rawName) throws IOException {
            String name = rawName.replace('\\', '/');
            if (!prefix.isEmpty() && !name.startsWith(prefix)) return null;
            String base = name.substring(name.lastIndexOf('/') + 1);
            int dot = base.lastIndexOf('.');
            if (dot >= 0 && blocked.contains(base.substring(dot).toLowerCase(Locale.ROOT))) return null;
            if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) throw new IOException("Unsafe archive path: " + rawName);
            for (String part : name.split("/")) {
                if (part.equals("..")) throw new IOException("Unsafe archive path: " + rawName);
            }
            File dest = new File(root, name);
            if (!dest.getCanonicalPath().startsWith(rootPath)) throw new IOException("Unsafe archive path: " + rawName);
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            return dest;
        }

        void add(long bytes) throws LimitException {
            total += bytes;
            if (total > max) throw new LimitException();
        }
    }

    public static String extract(String src, String dst, String prefix, long maxBytes, String blockedCsv) {
        try {
            File f = new File(src);
            byte[] m = new byte[6];
            try (InputStream in = new FileInputStream(f)) {
                if (in.read(m) < 6) return "UNSUPPORTED";
            }
            Set<String> blocked = new HashSet<>(Arrays.asList(blockedCsv.split(",")));
            File root = new File(dst);
            root.mkdirs();
            State st = new State(root, prefix == null ? "" : prefix, maxBytes, blocked);
            if (m[0] == 0x52 && m[1] == 0x61 && m[2] == 0x72 && m[3] == 0x21) {
                rar(f, st);
            } else if ((m[0] & 0xff) == 0x37 && m[1] == 0x7A && (m[2] & 0xff) == 0xBC && (m[3] & 0xff) == 0xAF) {
                sevenZ(f, st);
            } else {
                return "UNSUPPORTED";
            }
            return "OK:" + st.count + ":" + st.total;
        } catch (LimitException e) {
            return "ERR:LIMIT";
        } catch (Throwable t) {
            return "ERR:" + t;
        }
    }

    private static void rar(File f, State st) throws Exception {
        try (Archive a = new Archive(f)) {
            FileHeader fh;
            while ((fh = a.nextFileHeader()) != null) {
                if (fh.isDirectory()) continue;
                File dest = st.target(fh.getFileName());
                if (dest == null) continue;
                st.add(Math.max(0, fh.getFullUnpackSize()));
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    a.extractFile(fh, out);
                }
                st.count++;
            }
        }
    }

    private static void sevenZ(File f, State st) throws Exception {
        try (SevenZFile z = new SevenZFile(new RafChannel(f))) {
            SevenZArchiveEntry e;
            byte[] buf = new byte[65536];
            while ((e = z.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                File dest = st.target(e.getName());
                if (dest == null) continue;
                st.add(Math.max(0, e.getSize()));
                try (FileOutputStream out = new FileOutputStream(dest)) {
                    int n;
                    while ((n = z.read(buf)) > 0) out.write(buf, 0, n);
                }
                st.count++;
            }
        }
    }

    /** Read-only SeekableByteChannel over a file (avoids java.nio.file, which needs Android 8+). */
    private static final class RafChannel implements SeekableByteChannel {
        private final RandomAccessFile raf;

        RafChannel(File f) throws IOException {
            raf = new RandomAccessFile(f, "r");
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            int n = dst.remaining();
            if (n == 0) return 0;
            byte[] tmp = new byte[Math.min(n, 65536)];
            int r = raf.read(tmp, 0, tmp.length);
            if (r < 0) return -1;
            dst.put(tmp, 0, r);
            return r;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            throw new IOException("read-only");
        }

        @Override
        public long position() throws IOException {
            return raf.getFilePointer();
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            raf.seek(newPosition);
            return this;
        }

        @Override
        public long size() throws IOException {
            return raf.length();
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            throw new IOException("read-only");
        }

        @Override
        public boolean isOpen() {
            return raf.getChannel().isOpen();
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}
