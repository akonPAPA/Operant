package com.operant.ctl;

import com.sun.jna.platform.win32.Crypt32Util;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

interface ControlCredentialStore {
  ControlCredential load(String alias);

  void store(String alias, ControlCredential credential);

  void delete(String alias);

  Optional<ControlCredentialMetadata> metadata(String alias);

  static ControlCredentialStore production() {
    String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (os.contains("windows")) {
      String appData = System.getenv("APPDATA");
      if (appData == null || appData.isBlank()) {
        throw new ControlCredentialStoreException("Windows APPDATA is unavailable");
      }
      return new WindowsDpapiControlCredentialStore(
          Path.of(appData, "OrderPilot", "operantctl", "credentials"));
    }
    return new UnsupportedProductionControlCredentialStore();
  }
}

final class ControlCredential implements AutoCloseable {
  private static final Pattern SECRET_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");
  private static final int KEY_BYTES = 32;

  private final byte[] keyMaterial;

  ControlCredential(String secretHex) {
    if (secretHex == null || !SECRET_HEX.matcher(secretHex).matches()) {
      throw new ControlCredentialStoreException(
          "control credential material must be exactly 64 hexadecimal characters");
    }
    this.keyMaterial = java.util.HexFormat.of().parseHex(secretHex.toLowerCase(Locale.ROOT));
  }

  private ControlCredential(byte[] keyMaterial) {
    if (keyMaterial == null || keyMaterial.length != KEY_BYTES) {
      throw new ControlCredentialStoreException("control credential material is invalid");
    }
    this.keyMaterial = keyMaterial.clone();
  }

  static ControlCredential fromKeyMaterial(byte[] keyMaterial) {
    return new ControlCredential(keyMaterial);
  }

  byte[] keyMaterialCopy() {
    return keyMaterial.clone();
  }

  @Override
  public void close() {
    Arrays.fill(keyMaterial, (byte) 0);
  }
}

record ControlCredentialMetadata(String alias, Instant createdAt, Instant updatedAt) {}

final class ControlCredentialStoreException extends RuntimeException {
  ControlCredentialStoreException(String message) {
    super(message);
  }

  ControlCredentialStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}

final class WindowsDpapiControlCredentialStore implements ControlCredentialStore {
  private static final Pattern ALIAS_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$");
  private static final byte[] FORMAT_PREFIX = "OPERANTCTL_DPAPI_V1\n".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_BLOB_BYTES = 16 * 1024;

  private final Path directory;

  WindowsDpapiControlCredentialStore(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
  }

  @Override
  public ControlCredential load(String alias) {
    Path file = credentialPath(alias);
    try {
      rejectReparse(directory);
      if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
        throw new ControlCredentialStoreException("control credential is not available");
      }
      verifyRegularFile(file);
      long size = Files.size(file);
      if (size <= FORMAT_PREFIX.length || size > MAX_BLOB_BYTES) {
        throw new ControlCredentialStoreException("control credential blob is invalid");
      }
      byte[] blob = Files.readAllBytes(file);
      try {
        if (!startsWith(blob, FORMAT_PREFIX)) {
          throw new ControlCredentialStoreException("control credential blob version is unsupported");
        }
        byte[] encrypted = Base64.getDecoder().decode(
            new String(blob, FORMAT_PREFIX.length, blob.length - FORMAT_PREFIX.length, StandardCharsets.US_ASCII));
        byte[] clear = Crypt32Util.cryptUnprotectData(encrypted);
        try {
          return ControlCredential.fromKeyMaterial(clear);
        } finally {
          Arrays.fill(clear, (byte) 0);
        }
      } finally {
        Arrays.fill(blob, (byte) 0);
      }
    } catch (ControlCredentialStoreException failure) {
      throw failure;
    } catch (Exception failure) {
      throw new ControlCredentialStoreException("control credential could not be loaded", failure);
    }
  }

  @Override
  public void store(String alias, ControlCredential credential) {
    Path file = credentialPath(alias);
    Path temp = directory.resolve("." + encodedAlias(alias) + "." + UUID.randomUUID() + ".tmp");
    byte[] clear = credential.keyMaterialCopy();
    byte[] blob = null;
    try {
      ensureSecureDirectory();
      rejectUnsafeExistingTarget(file);
      byte[] encrypted = Crypt32Util.cryptProtectData(clear);
      String encoded = Base64.getEncoder().encodeToString(encrypted);
      blob = new byte[FORMAT_PREFIX.length + encoded.length()];
      System.arraycopy(FORMAT_PREFIX, 0, blob, 0, FORMAT_PREFIX.length);
      System.arraycopy(encoded.getBytes(StandardCharsets.US_ASCII), 0, blob, FORMAT_PREFIX.length, encoded.length());
      try (FileChannel channel = FileChannel.open(
          temp,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE)) {
        channel.write(ByteBuffer.wrap(blob));
        channel.force(true);
      }
      setOwnerOnlyAcl(temp);
      Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      setOwnerOnlyAcl(file);
      syncDirectoryBestEffort(directory);
    } catch (AtomicMoveNotSupportedException failure) {
      throw new ControlCredentialStoreException("atomic credential replacement is unavailable", failure);
    } catch (IOException failure) {
      throw new ControlCredentialStoreException("control credential could not be stored", failure);
    } finally {
      Arrays.fill(clear, (byte) 0);
      if (blob != null) {
        Arrays.fill(blob, (byte) 0);
      }
      try {
        Files.deleteIfExists(temp);
      } catch (IOException ignored) {
        // Best-effort cleanup only; temp name contains no secret material.
      }
    }
  }

  @Override
  public void delete(String alias) {
    try {
      rejectReparse(directory);
      Files.deleteIfExists(credentialPath(alias));
    } catch (IOException failure) {
      throw new ControlCredentialStoreException("control credential could not be deleted", failure);
    }
  }

  @Override
  public Optional<ControlCredentialMetadata> metadata(String alias) {
    Path file = credentialPath(alias);
    try {
      rejectReparse(directory);
    } catch (IOException failure) {
      throw new ControlCredentialStoreException("control credential metadata could not be read", failure);
    }
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    try {
      verifyRegularFile(file);
      BasicFileAttributes attributes = Files.readAttributes(
          file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      return Optional.of(new ControlCredentialMetadata(
          alias,
          attributes.creationTime().toInstant(),
          attributes.lastModifiedTime().toInstant()));
    } catch (IOException failure) {
      throw new ControlCredentialStoreException("control credential metadata could not be read", failure);
    }
  }

  private void ensureSecureDirectory() throws IOException {
    Files.createDirectories(directory);
    rejectReparse(directory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new ControlCredentialStoreException("control credential directory is invalid");
    }
    setOwnerOnlyAcl(directory);
  }

  private Path credentialPath(String alias) {
    String encoded = encodedAlias(alias);
    Path file = directory.resolve(encoded + ".dpapi").normalize();
    if (!file.startsWith(directory)) {
      throw new ControlCredentialStoreException("control credential alias is invalid");
    }
    return file;
  }

  private static String encodedAlias(String alias) {
    if (alias == null || !ALIAS_PATTERN.matcher(alias.trim()).matches()) {
      throw new ControlCredentialStoreException("control credential alias is invalid");
    }
    return Base64.getUrlEncoder().withoutPadding()
        .encodeToString(alias.trim().getBytes(StandardCharsets.UTF_8));
  }

  private static void verifyRegularFile(Path file) throws IOException {
    BasicFileAttributes attributes = Files.readAttributes(
        file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    if (!attributes.isRegularFile()) {
      throw new ControlCredentialStoreException("control credential file is invalid");
    }
  }

  private static void rejectUnsafeExistingTarget(Path file) throws IOException {
    if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    verifyRegularFile(file);
  }

  private static void rejectReparse(Path path) throws IOException {
    Path absolute = path.toAbsolutePath().normalize();
    Path current = absolute.getRoot();
    for (Path name : absolute) {
      current = current == null ? name : current.resolve(name);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      BasicFileAttributes attributes = Files.readAttributes(
          current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (Files.isSymbolicLink(current) || attributes.isOther()) {
        throw new ControlCredentialStoreException("control credential path uses an unsupported reparse point");
      }
    }
  }

  private static void setOwnerOnlyAcl(Path path) throws IOException {
    AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (acl == null) {
      throw new ControlCredentialStoreException("owner-only credential ACLs are unavailable");
    }
    UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
    EnumSet<AclEntryPermission> permissions = EnumSet.allOf(AclEntryPermission.class);
    permissions.remove(AclEntryPermission.WRITE_OWNER);
    acl.setAcl(java.util.List.of(AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(permissions)
        .build()));
  }

  private static void syncDirectoryBestEffort(Path path) {
    try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException ignored) {
      // Some Windows filesystems do not support forcing a directory handle; file fsync already ran.
    }
  }

  private static boolean startsWith(byte[] value, byte[] prefix) {
    if (value.length < prefix.length) {
      return false;
    }
    for (int index = 0; index < prefix.length; index++) {
      if (value[index] != prefix[index]) {
        return false;
      }
    }
    return true;
  }
}

final class InMemoryControlCredentialStore implements ControlCredentialStore {
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  @Override
  public ControlCredential load(String alias) {
    Entry entry = entries.get(alias);
    if (entry == null) {
      throw new ControlCredentialStoreException("control credential is not available");
    }
    return ControlCredential.fromKeyMaterial(entry.credential().keyMaterialCopy());
  }

  @Override
  public void store(String alias, ControlCredential credential) {
    Instant now = Instant.now();
    entries.compute(alias, (key, existing) -> new Entry(
        alias,
        ControlCredential.fromKeyMaterial(credential.keyMaterialCopy()),
        existing == null ? now : existing.metadata().createdAt(),
        now));
  }

  @Override
  public void delete(String alias) {
    entries.remove(alias);
  }

  @Override
  public Optional<ControlCredentialMetadata> metadata(String alias) {
    Entry entry = entries.get(alias);
    return entry == null ? Optional.empty() : Optional.of(entry.metadata());
  }

  private record Entry(String alias, ControlCredential credential, Instant createdAt, Instant updatedAt) {
    private ControlCredentialMetadata metadata() {
      return new ControlCredentialMetadata(alias, createdAt, updatedAt);
    }
  }
}

final class UnsupportedProductionControlCredentialStore implements ControlCredentialStore {
  @Override
  public ControlCredential load(String alias) {
    throw unsupported();
  }

  @Override
  public void store(String alias, ControlCredential credential) {
    throw unsupported();
  }

  @Override
  public void delete(String alias) {
    throw unsupported();
  }

  @Override
  public Optional<ControlCredentialMetadata> metadata(String alias) {
    throw unsupported();
  }

  private static ControlCredentialStoreException unsupported() {
    return new ControlCredentialStoreException(
        "OS-protected control credential store is unsupported on this platform");
  }
}