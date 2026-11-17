package com.example.baseplate.contract.arch;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AR-01：依赖方向靠 pom，不靠自觉。
 * surefire 的工作目录是模块目录，因此 .. 即聚合根。
 */
class PomBoundaryTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void apiHasNoThirdPartyDependency() throws Exception {
        assertNoForbidden("baseplate-api/pom.xml",
                Set.of("spring", "mybatis", "redis", "flowable", "jackson", "junit"));
    }

    @Test
    void domainHasNoFrameworkDependency() throws Exception {
        assertNoForbidden("baseplate-domain/pom.xml",
                Set.of("spring", "mybatis", "redis", "flowable", "jackson", "jpa", "hibernate", "jdbc"));
    }

    private void assertNoForbidden(String pomRelative, Set<String> forbidden) throws Exception {
        Path pom = ROOT.resolve(pomRelative);
        assertTrue(Files.exists(pom), "缺少 " + pom);

        var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        var nodes = doc.getElementsByTagName("dependency");
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element dep = (Element) nodes.item(i);
            String coord = (text(dep, "groupId") + ":" + text(dep, "artifactId")).toLowerCase(Locale.ROOT);
            for (String bad : forbidden) {
                if (coord.contains(bad)) {
                    hits.add(coord);
                }
            }
        }
        assertTrue(hits.isEmpty(), pomRelative + " 出现禁止依赖: " + hits);
    }

    private static String text(Element parent, String tag) {
        var list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent();
    }
}
