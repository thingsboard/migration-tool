/**
 * ThingsBoard, Inc. ("COMPANY") CONFIDENTIAL
 *
 * Copyright © 2016-2021 ThingsBoard, Inc. All Rights Reserved.
 *
 * NOTICE: All information contained herein is, and remains
 * the property of ThingsBoard, Inc. and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to ThingsBoard, Inc.
 * and its suppliers and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 *
 * Dissemination of this information or reproduction of this material is strictly forbidden
 * unless prior written permission is obtained from COMPANY.
 *
 * Access to the source code contained herein is hereby forbidden to anyone except current COMPANY employees,
 * managers or contractors who have executed Confidentiality and Non-disclosure agreements
 * explicitly covering such access.
 *
 * The copyright notice above does not evidence any actual or intended publication
 * or disclosure  of  this source code, which includes
 * information that is confidential and/or proprietary, and is a trade secret, of  COMPANY.
 * ANY REPRODUCTION, MODIFICATION, DISTRIBUTION, PUBLIC  PERFORMANCE,
 * OR PUBLIC DISPLAY OF OR THROUGH USE  OF THIS  SOURCE CODE  WITHOUT
 * THE EXPRESS WRITTEN CONSENT OF COMPANY IS STRICTLY PROHIBITED,
 * AND IN VIOLATION OF APPLICABLE LAWS AND INTERNATIONAL TREATIES.
 * THE RECEIPT OR POSSESSION OF THIS SOURCE CODE AND/OR RELATED INFORMATION
 * DOES NOT CONVEY OR IMPLY ANY RIGHTS TO REPRODUCE, DISCLOSE OR DISTRIBUTE ITS CONTENTS,
 * OR TO MANUFACTURE, USE, OR SELL ANYTHING THAT IT  MAY DESCRIBE, IN WHOLE OR IN PART.
 */
package org.thingsboard.tbcli.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.thingsboard.rest.client.RestClient;
import org.thingsboard.server.common.data.Dashboard;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.User;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.page.PageLink;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainConnectionInfo;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.widget.WidgetType;
import org.thingsboard.server.common.data.widget.WidgetsBundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
public abstract class CliServiceImpl implements CliService {

    private static final ObjectMapper mapper = new ObjectMapper();

    private static final String SRC_DIR = "src";
    private static final String MAIN_DIR = "main";
    private static final String DATA_DIR = "data";
    private static final String JSON_DIR = "json";
    private static final String CLI_INSTALL_DIR = "cli";
    private static final String RULE_CHAINS_DIR = "rule_chains";
    private static final String DASHBOARDS_DIR = "dashboards";
    private static final String WIDGETS_BUNDLES_DIR = "widgets_bundles";

    private final Map<String, RuleChainId> ruleChainsIds = new HashMap<>();

    @Value("${install.data_dir:}")
    private String dataDir;

    @Value("${rest.url}")
    private String restUrl;

    @Value("${rest.username}")
    private String username;

    @Value("${rest.password}")
    private String password;

    private RestClient restClient;
    private TenantId tenantId;
    private RuleChain rootRuleChain;

    protected void init() {
        login();
        tenantId = getTenantId();
        PageData<RuleChain> ruleChainTextPageData = restClient.getRuleChains(new PageLink(1, 0, "Generate Report"));
        ruleChainTextPageData.getData().stream().findFirst().ifPresent(ruleChain -> ruleChainsIds.put(ruleChain.getName(), ruleChain.getId()));
        rootRuleChain = restClient.getRuleChains(new PageLink(1, 0, "Root Rule Chain")).getData().stream().findFirst().get();
    }

    protected void destroy() {
    }

    private void login() {
        restClient = new RestClient(restUrl);
        restClient.login(username, password);
        log.info("Successfully login to: [{}] as [{}]", restUrl, username);
    }

    private TenantId getTenantId() {
        return restClient.getUser().map(User::getTenantId).orElse(null);
    }

    private String getDataDir() {
        if (!StringUtils.isEmpty(dataDir)) {
            if (!Paths.get(this.dataDir).toFile().isDirectory()) {
                log.info("dataDir: [{}]", dataDir);
                log.info("this.dataDir: [{}]", this.dataDir);
                throw new RuntimeException("'install.data_dir' property value is not a valid directory!");
            }
            return dataDir;
        } else {
            String workDir = System.getProperty("user.dir");
            if (workDir.endsWith("tb-cli")) {
                return Paths.get(workDir, SRC_DIR, MAIN_DIR, DATA_DIR).toString();
            } else {
                throw new RuntimeException("Not valid working directory: " + workDir + ". Please use either root project directory, application module directory or specify valid \"install.data_dir\" ENV variable to avoid automatic data directory lookup!");
            }
        }
    }

    private String getSolutionDirName() {
        return CLI_INSTALL_DIR;
    }

    private Path getRuleChainsDir() {
        return Paths.get(getDataDir(), JSON_DIR, getSolutionDirName(), RULE_CHAINS_DIR);
    }

    private Path getDashboardsDir() {
        return Paths.get(getDataDir(), JSON_DIR, getSolutionDirName(), DASHBOARDS_DIR);
    }

    private Path getWidgetsBundlesDir() {
        return Paths.get(getDataDir(), JSON_DIR, getSolutionDirName(), WIDGETS_BUNDLES_DIR);
    }

    private JsonNode readFileContentToJsonNode(Path path, String fileName) throws IOException {
        return mapper.readTree(Files.readAllBytes(path.resolve(fileName)));
    }

    private Dashboard createDashboard(String fileName) throws IOException {
        JsonNode dashboardJson = readFileContentToJsonNode(getDashboardsDir(), fileName);
        ObjectNode aliases = (ObjectNode) (dashboardJson.get("configuration").get("entityAliases"));
        aliases.forEach(this::replaceTenantIdRecursively);
        Dashboard dashboard = new Dashboard();
        dashboard.setTitle(dashboardJson.get("title").asText());
        dashboard.setConfiguration(dashboardJson.get("configuration"));
        dashboard = restClient.saveDashboard(dashboard);
        return dashboard;
    }

    protected RuleChain createRuleChain(String fileName, boolean root) throws IOException {
        return createRuleChain(fileName, root, Collections.emptyMap());
    }

    protected RuleChain createRuleChain(String fileName, boolean root, Map<String, String> ruleChainIdxs) throws IOException {
        JsonNode ruleChainJson = readFileContentToJsonNode(getRuleChainsDir(), fileName);
        if (!ruleChainIdxs.isEmpty()) {
            RuleChainMetaData ruleChainMetaData = mapper.treeToValue(ruleChainJson.get("metadata"), RuleChainMetaData.class);
            for (RuleChainConnectionInfo connectionInfo : ruleChainMetaData.getRuleChainConnections()) {
                String ruleChainName = ruleChainIdxs.get(connectionInfo.getAdditionalInfo().get("ruleChainNodeId").asText());
                if (!ruleChainsIds.containsKey(ruleChainName)) {
                    log.warn("Incorrect order of imported rule chains. Rule chain {} is loaded before: {}!", fileName, ruleChainName);
                    throw new RuntimeException("Incorrect import order");
                } else {
                    connectionInfo.setTargetRuleChainId(ruleChainsIds.get(ruleChainName));
                }
            }
            ((ObjectNode) ruleChainJson).replace("metadata", mapper.valueToTree(ruleChainMetaData));
        }
        replaceTenantIdRecursively(ruleChainJson);
        return createRuleChain(ruleChainJson, root);
    }

    private void replaceTenantIdRecursively(JsonNode root) {
        if (root.isArray()) {
            ArrayNode array = (ArrayNode) root;
            array.forEach(this::replaceTenantIdRecursively);
        } else if (root.isObject()) {
            if (!findAndReplaceEntityId(root)) {
                root.fields().forEachRemaining(e -> {
                    if (!findAndReplaceEntityId(e.getValue())) {
                        replaceTenantIdRecursively(e.getValue());
                    }
                });
            }
        }
    }

    private boolean findAndReplaceEntityId(JsonNode node) {
        if (node.isObject() && node.size() == 2) {
            ObjectNode value = (ObjectNode) node;
            if (value.has("id") && value.has("entityType") && EntityType.TENANT.name().equalsIgnoreCase(value.get("entityType").asText())) {
                value.put("id", tenantId.getId().toString());
                return true;
            }
        }
        return false;
    }

    protected RuleChain createRuleChain(JsonNode ruleChainJson, boolean root) throws JsonProcessingException {
        RuleChain ruleChain = mapper.treeToValue(ruleChainJson.get("ruleChain"), RuleChain.class);
        RuleChainMetaData ruleChainMetaData = mapper.treeToValue(ruleChainJson.get("metadata"), RuleChainMetaData.class);
        RuleChain savedRuleChain = restClient.saveRuleChain(ruleChain);
        ruleChainMetaData.setRuleChainId(savedRuleChain.getId());
        restClient.saveRuleChainMetaData(ruleChainMetaData);
        if (root) {
            restClient.setRootRuleChain(savedRuleChain.getId());
        } else {
            ruleChainsIds.put(savedRuleChain.getName(), savedRuleChain.getId());
        }
        return savedRuleChain;
    }

    protected void saveServerSideAttributes(EntityId entityId, JsonNode attributes) {
        if (attributes != null && !attributes.isNull() && attributes.size() > 0) {
            log.info("[{}] Saving attributes: {}", entityId, attributes);
            restClient.saveEntityAttributesV1(entityId, DataConstants.SERVER_SCOPE, attributes);
        }
    }

    protected JsonNode readDefinitions(Path dataDir, String fileName) {
        try {
            return mapper.readTree(Files.readAllBytes(dataDir.resolve(fileName)));
        } catch (NoSuchFileException e) {
            log.warn("Could not read [{}] file", fileName);
        } catch (IOException e) {
            log.warn("Could not read [{}] file", fileName, e);
        }
        return null;
    }

    protected WidgetsBundle createWidgetsBundle(String filename) throws IOException {
        JsonNode widgetsBundleJson = readFileContentToJsonNode(getWidgetsBundlesDir(), filename);
        WidgetsBundle widgetsBundle = restClient.saveWidgetsBundle(mapper.treeToValue(widgetsBundleJson.get("widgetsBundle"), WidgetsBundle.class));
        for (JsonNode widgetTypeJson : widgetsBundleJson.get("widgetTypes")) {
            WidgetType widgetType = mapper.treeToValue(widgetTypeJson, WidgetType.class);
            widgetType.setBundleAlias(widgetsBundle.getAlias());
            restClient.saveWidgetType(widgetType);
        }
        return widgetsBundle;
    }
}
