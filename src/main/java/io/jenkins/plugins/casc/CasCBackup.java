package io.jenkins.plugins.casc;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.jenkins.plugins.casc.impl.DefaultConfiguratorRegistry;
import io.jenkins.plugins.casc.model.CNode;
import io.jenkins.plugins.casc.model.Mapping;
import io.jenkins.plugins.casc.model.Scalar;
import io.jenkins.plugins.casc.model.Sequence;
import io.jenkins.plugins.casc.snakeyaml.DumperOptions;
import io.jenkins.plugins.casc.snakeyaml.emitter.Emitter;
import io.jenkins.plugins.casc.snakeyaml.error.YAMLException;
import io.jenkins.plugins.casc.snakeyaml.nodes.MappingNode;
import io.jenkins.plugins.casc.snakeyaml.nodes.Node;
import io.jenkins.plugins.casc.snakeyaml.nodes.NodeTuple;
import io.jenkins.plugins.casc.snakeyaml.nodes.ScalarNode;
import io.jenkins.plugins.casc.snakeyaml.nodes.SequenceNode;
import io.jenkins.plugins.casc.snakeyaml.nodes.Tag;
import io.jenkins.plugins.casc.snakeyaml.resolver.Resolver;
import io.jenkins.plugins.casc.snakeyaml.serializer.Serializer;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;

import javax.inject.Inject;
import javax.servlet.ServletContext;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.jenkins.plugins.casc.snakeyaml.DumperOptions.FlowStyle.BLOCK;
import static io.jenkins.plugins.casc.snakeyaml.DumperOptions.ScalarStyle.*;

@Extension(ordinal = 100)
public class CasCBackup extends SaveableListener {
    private static final Logger LOGGER = Logger.getLogger(CasCBackup.class.getName());

    private static final String DEFAULT_JENKINS_YAML_PATH = "jenkins.yaml";
    private static final String cascDirectory = "/WEB-INF/" + DEFAULT_JENKINS_YAML_PATH + ".d/";

    @Override
    public void onChange(Saveable o, XmlFile file) {
        // only take care of the configuration which controlled by casc
        if (!(o instanceof GlobalConfiguration)) {
            return;
        }

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            export(buf);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "error happen when exporting the whole config into a YAML", e);
            return;
        }

        final ServletContext servletContext = Jenkins.get().servletContext;
        try {
            URL bundled = servletContext.getResource(cascDirectory);
            if (bundled != null) {
                File cascDir = new File(bundled.getFile());

                boolean hasDir = false;
                if(!cascDir.exists()) {
                    hasDir = cascDir.mkdirs();
                } else if (cascDir.isFile()) {
                    LOGGER.severe(String.format("%s is a regular file", cascDir));
                } else {
                    hasDir = true;
                }

                if(hasDir) {
                    File backupFile = new File(cascDir, "user.yaml");
                    try (OutputStream writer = new FileOutputStream(backupFile)) {
                        writer.write(buf.toByteArray());

                        LOGGER.fine(String.format("backup file was saved, %s", backupFile.getAbsolutePath()));
                    } catch (IOException e) {
                        LOGGER.log(Level.WARNING, String.format("error happen when saving %s", backupFile.getAbsolutePath()), e);
                    }
                } else {
                    LOGGER.severe(String.format("cannot create casc backup directory %s", cascDir));
                }
            }
        } catch (MalformedURLException e) {
            LOGGER.log(Level.WARNING, String.format("error happen when finding %s", cascDirectory), e);
        }
    }

    @Inject
    private DefaultConfiguratorRegistry registry;
    private void export(OutputStream out) throws Exception {

        final List<NodeTuple> tuples = new ArrayList<>();

        final ConfigurationContext context = new ConfigurationContext(registry);
        for (RootElementConfigurator root : RootElementConfigurator.all()) {
            final CNode config = root.describe(root.getTargetComponent(context), context);
            final Node valueNode = toYaml(config);
            if (valueNode == null) continue;
            tuples.add(new NodeTuple(
                    new ScalarNode(Tag.STR, root.getName(), null, null, PLAIN),
                    valueNode));
        }

        MappingNode root = new MappingNode(Tag.MAP, tuples, BLOCK);
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            serializeYamlNode(root, writer);
        } catch (IOException e) {
            throw new YAMLException(e);
        }
    }

    private Node toYaml(CNode config) throws ConfiguratorException {

        if (config == null) return null;

        switch (config.getType()) {
            case MAPPING:
                final Mapping mapping = config.asMapping();
                final List<NodeTuple> tuples = new ArrayList<>();
                final List<Map.Entry<String, CNode>> entries = new ArrayList<>(mapping.entrySet());
                entries.sort(Comparator.comparing(Map.Entry::getKey));
                for (Map.Entry<String, CNode> entry : entries) {
                    final Node valueNode = toYaml(entry.getValue());
                    if (valueNode == null) continue;
                    tuples.add(new NodeTuple(
                            new ScalarNode(Tag.STR, entry.getKey(), null, null, PLAIN),
                            valueNode));

                }
                if (tuples.isEmpty()) return null;

                return new MappingNode(Tag.MAP, tuples, BLOCK);

            case SEQUENCE:
                final Sequence sequence = config.asSequence();
                List<Node> nodes = new ArrayList<>();
                for (CNode cNode : sequence) {
                    final Node valueNode = toYaml(cNode);
                    if (valueNode == null) continue;
                    nodes.add(valueNode);
                }
                if (nodes.isEmpty()) return null;
                return new SequenceNode(Tag.SEQ, nodes, BLOCK);

            case SCALAR:
            default:
                final Scalar scalar = config.asScalar();
                final String value = scalar.getValue();
                if (value == null || value.length() == 0) return null;

                final DumperOptions.ScalarStyle style;
                if (scalar.getFormat().equals(Scalar.Format.MULTILINESTRING) && !scalar.isRaw()) {
                    style = LITERAL;
                } else if (scalar.isRaw()) {
                    style = PLAIN;
                } else {
                    style = DOUBLE_QUOTED;
                }

                return new ScalarNode(getTag(scalar.getFormat()), value, null, null, style);
        }
    }

    private Tag getTag(Scalar.Format format) {
        switch (format) {
            case NUMBER:
                return Tag.INT;
            case FLOATING:
                return Tag.FLOAT;
            case BOOLEAN:
                return Tag.BOOL;
            case STRING:
            case MULTILINESTRING:
            default:
                return Tag.STR;
        }
    }

    private static void serializeYamlNode(Node root, Writer writer) throws IOException {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(BLOCK);
        options.setDefaultScalarStyle(PLAIN);
        options.setSplitLines(true);
        options.setPrettyFlow(true);
        Serializer serializer = new Serializer(new Emitter(writer, options), new Resolver(),
                options, null);
        serializer.open();
        serializer.serialize(root);
        serializer.close();
    }
}