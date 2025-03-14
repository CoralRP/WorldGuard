/*
 * WorldGuard, a suite of tools for Minecraft
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldGuard team and contributors
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.sk89q.worldguard.protection.managers.storage.file;

import static com.google.common.base.Preconditions.checkNotNull;

import com.sk89q.util.yaml.YAMLFormat;
import com.sk89q.util.yaml.YAMLNode;
import com.sk89q.util.yaml.YAMLProcessor;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.FlagUtil;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionDifference;
import com.sk89q.worldguard.protection.managers.storage.DifferenceSaveException;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabase;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabaseUtils;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.DumperOptions.FlowStyle;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.parser.ParserException;
import org.yaml.snakeyaml.representer.Representer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A store that persists regions in a YAML-encoded file.
 */
public class YamlRegionFile implements RegionDatabase {

    private static final Logger log = Logger.getLogger(YamlRegionFile.class.getCanonicalName());
    private static final Yaml ERROR_DUMP_YAML;

    private static final String FILE_HEADER = "#\r\n" +
            "# WorldGuard regions file\r\n" +
            "#\r\n" +
            "# WARNING: THIS FILE IS AUTOMATICALLY GENERATED. If you modify this file by\r\n" +
            "# hand, be aware that A SINGLE MISTYPED CHARACTER CAN CORRUPT THE FILE. If\r\n" +
            "# WorldGuard is unable to parse the file, your regions will FAIL TO LOAD and\r\n" +
            "# the contents of this file will reset. Please use a YAML validator such as\r\n" +
            "# http://yaml-online-parser.appspot.com (for smaller files).\r\n" +
            "#\r\n" +
            "# REMEMBER TO KEEP PERIODICAL BACKUPS.\r\n" +
            "#";

    private final String name;
    private final File file;

    static {
        DumperOptions options = new DumperOptions();
        options.setIndent(4);
        options.setDefaultFlowStyle(FlowStyle.AUTO);

        ERROR_DUMP_YAML = new Yaml(new SafeConstructor(new LoaderOptions()), new Representer(new DumperOptions()), options);
    }

    /**
     * Create a new instance.
     *
     * @param name the name of this store
     * @param file the file
     */
    public YamlRegionFile(String name, File file) {
        checkNotNull(name, "name");
        checkNotNull(file, "file");
        this.name = name;
        this.file = file;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Set<ProtectedRegion> loadAll(FlagRegistry flagRegistry) throws StorageException {
        Map<String, ProtectedRegion> loaded = new HashMap<>();

        YAMLProcessor config = createYamlProcessor(file);
        try {
            config.load();
        } catch (FileNotFoundException e) {
            return new HashSet<>(loaded.values());
        } catch (IOException | ParserException e) {
            throw new StorageException("Failed to load region data from '" + file + "'", e);
        }

        Map<String, YAMLNode> regionData = config.getNodes("regions");

        if (regionData == null) {
            return Collections.emptySet(); // No regions are even configured
        }

        Map<ProtectedRegion, String> parentSets = new LinkedHashMap<>();

        for (Map.Entry<String, YAMLNode> entry : regionData.entrySet()) {
            String id = entry.getKey();
            YAMLNode node = entry.getValue();

            String type = node.getString("type");
            ProtectedRegion region;

            try {
                if (type == null) {
                    log.warning("Undefined region type for region '" + id + "'!\n" +
                            "Here is what the region data looks like:\n\n" + toYamlOutput(entry.getValue().getMap()) + "\n");
                    continue;
                } else if (type.equals("cuboid")) {
                    Vector3 pt1 = checkNotNull(node.getVector("min"));
                    Vector3 pt2 = checkNotNull(node.getVector("max"));
                    BlockVector3 min = pt1.getMinimum(pt2).toBlockPoint();
                    BlockVector3 max = pt1.getMaximum(pt2).toBlockPoint();
                    region = new ProtectedCuboidRegion(id, min, max);
                } else if (type.equals("poly2d")) {
                    Integer minY = checkNotNull(node.getInt("min-y"));
                    Integer maxY = checkNotNull(node.getInt("max-y"));
                    List<BlockVector2> points = node.getBlockVector2List("points", null);
                    region = new ProtectedPolygonalRegion(id, points, minY, maxY);
                } else if (type.equals("global")) {
                    region = new GlobalProtectedRegion(id);
                } else {
                    log.warning("Unknown region type for region '" + id + "'!\n" +
                            "Here is what the region data looks like:\n\n" + toYamlOutput(entry.getValue().getMap()) + "\n");
                    continue;
                }

                Integer priority = checkNotNull(node.getInt("priority"));
                region.setPriority(priority);
                setFlags(flagRegistry, region, node.getNode("flags"));
                region.setOwners(parseDomain(node.getNode("owners")));
                region.setMembers(parseDomain(node.getNode("members")));

                loaded.put(id, region);

                String parentId = node.getString("parent");
                if (parentId != null) {
                    parentSets.put(region, parentId);
                }
            } catch (NullPointerException e) {
                log.log(Level.WARNING,
                        "Unexpected NullPointerException encountered during parsing for the region '" + id + "'!\n" +
                                "Here is what the region data looks like:\n\n" + toYamlOutput(entry.getValue().getMap()) +
                                "\n\nNote: This region will disappear as a result!", e);
            }
        }

        // Relink parents
        RegionDatabaseUtils.relinkParents(loaded, parentSets);

        return new HashSet<>(loaded.values());
    }

    @Override
    public void saveAll(Set<ProtectedRegion> regions) throws StorageException {
        checkNotNull(regions);

        File tempFile = new File(file.getParentFile(), file.getName() + ".tmp");
        YAMLProcessor config = createYamlProcessor(tempFile);

        config.clear();

        YAMLNode regionsNode = config.addNode("regions");
        Map<String, Object> map = regionsNode.getMap();

        for (ProtectedRegion region : regions) {
            Map<String, Object> nodeMap = new HashMap<>();
            map.put(region.getId(), nodeMap);
            YAMLNode node = new YAMLNode(nodeMap, false);

            if (region instanceof ProtectedCuboidRegion) {
                ProtectedCuboidRegion cuboid = (ProtectedCuboidRegion) region;
                node.setProperty("type", "cuboid");
                node.setProperty("min", cuboid.getMinimumPoint());
                node.setProperty("max", cuboid.getMaximumPoint());
            } else if (region instanceof ProtectedPolygonalRegion) {
                ProtectedPolygonalRegion poly = (ProtectedPolygonalRegion) region;
                node.setProperty("type", "poly2d");
                node.setProperty("min-y", poly.getMinimumPoint().getBlockY());
                node.setProperty("max-y", poly.getMaximumPoint().getBlockY());

                List<Map<String, Object>> points = new ArrayList<>();
                for (BlockVector2 point : poly.getPoints()) {
                    Map<String, Object> data = new HashMap<>();
                    data.put("x", point.getBlockX());
                    data.put("z", point.getBlockZ());
                    points.add(data);
                }

                node.setProperty("points", points);
            } else if (region instanceof GlobalProtectedRegion) {
                node.setProperty("type", "global");
            } else {
                node.setProperty("type", region.getClass().getCanonicalName());
            }

            node.setProperty("priority", region.getPriority());
            node.setProperty("flags", getFlagData(region));
            node.setProperty("owners", getDomainData(region.getOwners()));
            node.setProperty("members", getDomainData(region.getMembers()));

            ProtectedRegion parent = region.getParent();
            if (parent != null) {
                node.setProperty("parent", parent.getId());
            }
        }

        config.setHeader(FILE_HEADER);
        config.save();

        //noinspection ResultOfMethodCallIgnored
        file.delete();
        if (!tempFile.renameTo(file)) {
            throw new StorageException("Failed to rename temporary regions file to " + file.getAbsolutePath());
        }
    }

    @Override
    public void saveChanges(RegionDifference difference) throws DifferenceSaveException {
        throw new DifferenceSaveException("Not supported");
    }

    private DefaultDomain parseDomain(YAMLNode node) {
        if (node == null) {
            return new DefaultDomain();
        }

        DefaultDomain domain = new DefaultDomain();

        for (String name : node.getStringList("players", null)) {
            if (!name.isEmpty()) {
                domain.addPlayer(name);
            }
        }

        for (String stringId : node.getStringList("unique-ids", null)) {
            try {
                domain.addPlayer(UUID.fromString(stringId));
            } catch (IllegalArgumentException e) {
                log.log(Level.WARNING, "Failed to parse UUID '" + stringId + "'", e);
            }
        }

        for (String name : node.getStringList("groups", null)) {
            if (!name.isEmpty()) {
                domain.addGroup(name);
            }
        }

        return domain;
    }

    private Map<String, Object> getFlagData(ProtectedRegion region) {
        return FlagUtil.marshal(region.getFlags());
    }

    private void setFlags(FlagRegistry flagRegistry, ProtectedRegion region, YAMLNode flagsData) {
        if (flagsData != null) {
            region.setFlags(flagRegistry.unmarshal(flagsData.getMap(), true));
        }
    }

    private Map<String, Object> getDomainData(DefaultDomain domain) {
        Map<String, Object> domainData = new HashMap<>();

        setDomainData(domainData, "players", domain.getPlayers());
        setDomainData(domainData, "unique-ids", domain.getUniqueIds());
        setDomainData(domainData, "groups", domain.getGroups());

        return domainData;
    }

    private void setDomainData(Map<String, Object> domainData, String key, Set<?> domain) {
        if (domain.isEmpty()) {
            return;
        }

        List<String> list = new ArrayList<>();

        for (Object str : domain) {
            list.add(String.valueOf(str));
        }

        domainData.put(key, list);
    }

    /**
     * Create a YAML processor instance.
     *
     * @param file the file
     * @return a processor instance
     */
    private YAMLProcessor createYamlProcessor(File file) {
        checkNotNull(file);
        return new YAMLProcessor(file, false, YAMLFormat.COMPACT);
    }

    /**
     * Dump the given object as YAML for debugging purposes.
     *
     * @param object the object
     * @return the YAML string or an error string if dumping fails
     */
    private static String toYamlOutput(Object object) {
        try {
            return ERROR_DUMP_YAML.dump(object).replaceAll("(?m)^", "\t");
        } catch (Throwable t) {
            return "<error while dumping object>";
        }
    }

}
