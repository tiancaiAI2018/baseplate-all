package com.example.baseplate.agent.application;

import com.example.baseplate.agent.adapter.out.persistence.AgentSkillIndexRepository;
import com.example.baseplate.agent.config.AgentProperties;
import io.agentscope.core.skill.repository.FileSystemSkillRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能本地目录扫描。
 *
 * <p>为什么内容不落库：技能是运维频繁修改的 Markdown，放在本地目录可以直接编辑；
 * 数据库只维护「有哪些技能、是否有效、校验和」这份索引，页面据此展示与诊断。</p>
 */
public final class SkillIndexService {

    private static final Logger log = LoggerFactory.getLogger(SkillIndexService.class);
    private static final String TENANT = "*";
    private static final String SKILL_FILE = "SKILL.md";
    private static final Pattern NAME = Pattern.compile("(?m)^name:\\s*(.+)$");
    private static final Pattern DESCRIPTION = Pattern.compile("(?m)^description:\\s*(.+)$");
    private static final Pattern VERSION = Pattern.compile("(?m)^version:\\s*(.+)$");
    private static final Pattern MODES = Pattern.compile("(?m)^modes:\\s*(.+)$");

    private final AgentSkillIndexRepository repository;
    private final AgentProperties properties;

    public SkillIndexService(AgentSkillIndexRepository repository, AgentProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public record SkillView(String skillName, String description, String path, String version,
                            boolean enabled, String status, Object lastScanAt) {
    }

    public List<SkillView> list(String tenant) {
        return repository.list(tenant).stream().map(row -> new SkillView(
                row.skillName(), row.description(), row.path(), row.version(),
                row.enabled(), row.status(), row.lastScanAt())).toList();
    }

    /** 启动与手动重扫共用：读目录、算校验和、同步索引。 */
    public int scan() {
        Path root = skillRoot();
        if (root == null) {
            repository.markMissing(TENANT, List.of());
            return 0;
        }
        List<String> present = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path skillFile = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(skillFile)) {
                    continue;
                }
                String content = Files.readString(skillFile, StandardCharsets.UTF_8);
                String name = extract(NAME, content, dir.getFileName().toString());
                String description = extract(DESCRIPTION, content, "");
                String version = extract(VERSION, content, "1");
                repository.upsert(TENANT, name, description,
                        root.relativize(skillFile).toString().replace('\\', '/'),
                        version, sha256(content));
                present.add(name);
            }
        } catch (IOException e) {
            log.warn("扫描技能目录失败 {}: {}", root, e.toString());
            return 0;
        }
        repository.markMissing(TENANT, present);
        log.info("技能目录扫描完成: {} 个（{}）", present.size(), root);
        return present.size();
    }

    /** 目录存在且有技能时才挂载，FileSystemSkillRepository 对空目录会直接抛异常。 */
    public boolean available() {
        Path root = skillRoot();
        if (root == null) {
            return false;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.anyMatch(dir -> Files.isRegularFile(dir.resolve(SKILL_FILE)));
        } catch (IOException e) {
            return false;
        }
    }

    public FileSystemSkillRepository repository() {
        return new FileSystemSkillRepository(skillRoot(), true, "baseplate");
    }

    public boolean exists(String skillName) {
        if (skillName == null || skillName.isBlank()) {
            return false;
        }
        Path root = skillRoot();
        if (root == null) {
            return false;
        }
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String name = extract(NAME, Files.readString(file, StandardCharsets.UTF_8),
                        dir.getFileName().toString());
                if (skillName.equals(name)) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    public List<String> byMode(String mode) {
        Path root = skillRoot();
        if (root == null || mode == null || mode.isBlank()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Path file = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                for (String declared : extract(MODES, raw, "").split("[,\\s]+")) {
                    if (declared.equalsIgnoreCase(mode)) {
                        matched.add(extract(NAME, raw, dir.getFileName().toString()));
                        break;
                    }
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return matched;
    }

    // ==================== 在线编辑 / 导入 ====================

    public record SkillDetail(String skillName, String description, String version, String path,
                              String content) {
    }

    /** 读取技能全文，供页面编辑。 */
    public SkillDetail detail(String tenant, String skillName) {
        AgentSkillIndexRepository.SkillRow row = repository.find(tenant, skillName)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在：" + skillName));
        Path file = resolveInside(requireRoot(), row.path());
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("技能文件不存在：" + row.path());
        }
        return new SkillDetail(row.skillName(), row.description(), row.version(), row.path(),
                readString(file));
    }

    /** 新建技能目录 + SKILL.md；正文没带 frontmatter 时自动补齐。 */
    public SkillDetail create(String tenant, String skillName, String description, String content,
                              String modes) {
        validateSkillName(skillName);
        Path root = requireRoot();
        if (repository.find(tenant, skillName.trim()).isPresent()) {
            throw new IllegalArgumentException("技能已存在：" + skillName);
        }
        Path dir = resolveInside(root, skillName.trim());
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(SKILL_FILE),
                    compose(skillName.trim(), description, content, modes), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入技能失败：" + e.getMessage(), e);
        }
        scan();
        return detail(tenant, skillName.trim());
    }

    /** 保存正文。描述、版本以文件里的 frontmatter 为准，避免索引与文件两处各改一份。 */
    public SkillDetail update(String tenant, String skillName, String content) {
        AgentSkillIndexRepository.SkillRow row = repository.find(tenant, skillName)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在：" + skillName));
        Path file = resolveInside(requireRoot(), row.path());
        try {
            Files.writeString(file, content == null ? "" : content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存技能失败：" + e.getMessage(), e);
        }
        scan();
        return detail(tenant, row.skillName());
    }

    /** 删除技能目录；只允许删 skills 下的直接子目录，防止误删根目录。 */
    public void delete(String tenant, String skillName) {
        AgentSkillIndexRepository.SkillRow row = repository.find(tenant, skillName)
                .orElseThrow(() -> new IllegalArgumentException("技能不存在：" + skillName));
        Path root = requireRoot();
        Path dir = resolveInside(root, row.path()).getParent();
        if (dir == null || !root.equals(dir.getParent())) {
            throw new IllegalArgumentException("技能目录结构异常，拒绝删除：" + row.path());
        }
        deleteRecursively(dir);
        // 目录已删就同步移除索引，否则 scan 会把它标成 MISSING 留在列表里
        repository.delete(tenant, row.skillName());
        scan();
    }

    /** 导入 zip：支持压缩包根目录就是技能、以及包含多个技能目录两种结构。 */
    public int importZip(String archiveName, byte[] data) {
        Path root = requireRoot();
        Path temp = null;
        try {
            temp = Files.createTempDirectory("bp-skills-");
            unzip(data, temp);
            List<Path> skillDirs = findSkillDirs(temp);
            if (skillDirs.isEmpty()) {
                throw new IllegalArgumentException("压缩包里没有找到 SKILL.md");
            }
            int count = 0;
            for (Path dir : skillDirs) {
                // 压缩包根目录本身就是技能时，用压缩包文件名当目录名
                String name = temp.equals(dir) ? baseName(archiveName) : dir.getFileName().toString();
                validateSkillName(name);
                copyRecursively(dir, resolveInside(root, name));
                count++;
            }
            scan();
            return count;
        } catch (IOException e) {
            throw new IllegalStateException("解压技能包失败：" + e.getMessage(), e);
        } finally {
            if (temp != null) {
                deleteRecursively(temp);
            }
        }
    }

    /** 文件夹上传：key 形如「技能目录/相对路径」，原样落到 skills 根下。 */
    public int importFiles(Map<String, byte[]> files) {
        Path root = requireRoot();
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("没有收到文件");
        }
        int count = 0;
        try {
            for (Map.Entry<String, byte[]> entry : files.entrySet()) {
                String relative = entry.getKey().replace('\\', '/');
                Path target = resolveInside(root, relative);
                Files.createDirectories(target.getParent());
                Files.write(target, entry.getValue());
                // 每个技能目录只计一次：SKILL.md 出现即视为一个技能
                if (relative.equals(SKILL_FILE) || relative.endsWith("/" + SKILL_FILE)) {
                    count++;
                }
            }
            scan();
            return count;
        } catch (IOException e) {
            throw new IllegalStateException("写入技能文件失败：" + e.getMessage(), e);
        }
    }

    private Path requireRoot() {
        Path root = skillRoot();
        if (root == null) {
            throw new IllegalStateException("技能目录不存在：" + properties.getSkillDir());
        }
        return root;
    }

    /** 所有写入都先规范化再校验前缀，杜绝 ../ 穿越到技能目录之外。 */
    private static Path resolveInside(Path root, String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("非法路径：" + relative);
        }
        return target;
    }

    private static void validateSkillName(String name) {
        if (name == null || name.isBlank() || name.length() > 64
                || name.contains("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("技能名称不合法：不能为空或包含路径分隔符");
        }
    }

    private static String compose(String name, String description, String content, String modes) {
        if (content != null && content.stripLeading().startsWith("---")) {
            return content;
        }
        String modesLine = modes == null || modes.isBlank() ? "" : "modes: " + modes.trim() + "\n";
        return "---\nname: " + name + "\ndescription: "
                + (description == null ? "" : description) + "\nversion: 1\n" + modesLine + "---\n\n"
                + (content == null ? "" : content);
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取技能失败：" + e.getMessage(), e);
        }
    }

    private static String baseName(String archiveName) {
        if (archiveName == null || archiveName.isBlank()) {
            return "imported-skill";
        }
        String name = archiveName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.toLowerCase().endsWith(".zip")
                ? name.substring(0, name.length() - 4) : name;
    }

    private static void unzip(byte[] data, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data),
                StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path path = resolveInside(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(path);
                } else {
                    Files.createDirectories(path.getParent());
                    Files.copy(zip, path, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 找出真正包含 SKILL.md 的目录；父目录已命中的子目录不重复导入。 */
    private static List<Path> findSkillDirs(Path root) throws IOException {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root, 4)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!SKILL_FILE.equals(path.getFileName().toString())) {
                    continue;
                }
                Path dir = path.getParent();
                if (result.stream().noneMatch(dir::startsWith)) {
                    result.add(dir);
                }
            }
        }
        return result;
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // 尽力删除；占用中的文件交给下一次操作
                }
            });
        } catch (IOException e) {
            log.warn("删除目录失败 {}: {}", path, e.toString());
        }
    }

    private Path skillRoot() {
        Path root = Paths.get(properties.getSkillDir()).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return null;
        }
        return root;
    }

    private static String extract(Pattern pattern, String content, String fallback) {
        Matcher matcher = pattern.matcher(content);
        return matcher.find() ? matcher.group(1).trim() : fallback;
    }

    private static String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
