/*
 * Copyright (C) 2023 Fraunhofer Institut IOSB, Fraunhoferstr. 1, D 76131
 * Karlsruhe, Germany.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package de.fraunhofer.iosb.ilt.frostserver.plugin.batchprocessing.json;

import static de.fraunhofer.iosb.ilt.frostserver.util.Constants.CONTENT_TYPE_APPLICATION_JSON;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import de.fraunhofer.iosb.ilt.frostserver.path.Version;
import de.fraunhofer.iosb.ilt.frostserver.plugin.batchprocessing.batch.Batch;
import de.fraunhofer.iosb.ilt.frostserver.plugin.batchprocessing.batch.Content;
import de.fraunhofer.iosb.ilt.frostserver.plugin.batchprocessing.batch.Part;
import de.fraunhofer.iosb.ilt.frostserver.service.ServiceRequest;
import de.fraunhofer.iosb.ilt.frostserver.settings.CoreSettings;
import de.fraunhofer.iosb.ilt.frostserver.util.HttpMethod;
import de.fraunhofer.iosb.ilt.frostserver.util.StringHelper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser for JSON request.
 */
public class JsonBatch extends Batch<Content> {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonBatch.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public JsonBatch(Version batchVersion, CoreSettings settings, boolean isChangeSet) {
        super(batchVersion, settings, isChangeSet);
    }

    @Override
    public boolean parse(ServiceRequest serviceRequest) {
        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(serviceRequest.getContentString());
            JsonNode reqNode = rootNode.path("requests");
            Iterator<JsonNode> requests = reqNode.elements();

            Map<String, Batch<Content>> atomicityGroupToBatch = new HashMap<>();
            while (requests.hasNext()) {
                JsonNode req = requests.next();
                Part<Content> reqPart = new Part<>(this.batchVersion, this.settings, false, "");
                if (req.has("atomicityGroup")) {
                    Batch<Content> group = getAtomicityGroup(atomicityGroupToBatch,
                            req.get("atomicityGroup").textValue());
                    group.addPart(reqPart);
                    reqPart.setInChangeSet(true);
                } else {
                    this.addPart(reqPart);
                }
                JsonRequest request = new JsonRequest(this.batchVersion);
                reqPart.setContent(request);
                for (String property : Arrays.asList("id", "method", "url")) {
                    if (StringHelper.isNullOrEmpty(property)) {
                        parseFailed = true;
                        errors.add("All requests must have a valid " + property);
                    }
                }
                if (!parseFailed) {
                    request.setContentId(req.get("id").textValue());
                    request.setMethod(HttpMethod.fromString(req.get("method").textValue()));
                    request.parseUrl(req.get("url").textValue());
                    if (req.has("body")) {
                        request.addData(OBJECT_MAPPER.writeValueAsString(req.get("body")));
                    }
                    if (req.has("headers") && req.get("headers").isObject()) {
                        JsonNode headers = req.get("headers");
                        Map<String, String> innerHeaders = request.getInnerHeaders();
                        Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
                        while (fields.hasNext()) {
                            Map.Entry<String, JsonNode> next = fields.next();
                            innerHeaders.put(next.getKey(), next.getValue().asText());
                        }
                    }
                }
            }
            return true;
        } catch (IOException exc) {
            LOGGER.error("Failed to read data.", exc);
            return false;
        }
    }

    private Batch<Content> getAtomicityGroup(Map<String, Batch<Content>> atomicityGroupToBatch,
            String atomicityGroupId) {
        Batch<Content> group;
        if (atomicityGroupToBatch.containsKey(atomicityGroupId)) {
            group = atomicityGroupToBatch.get(atomicityGroupId);
        } else {
            group = new JsonBatch(this.batchVersion, this.settings, true);
            atomicityGroupToBatch.put(atomicityGroupId, group);
            Part<Content> atomicityGroupPart = new Part<>(this.batchVersion, this.settings, true, "");
            atomicityGroupPart.setContent(group);
            this.addPart(atomicityGroupPart);
        }
        return group;
    }

    @Override
    public String getContent(boolean allHeaders) {
        List<String> responses = new ArrayList<>();
        addResponses(responses, this);
        ObjectNode rootNode = OBJECT_MAPPER.createObjectNode();
        ArrayNode arrayNode = OBJECT_MAPPER.createArrayNode();
        responses.forEach(s -> arrayNode.addRawValue(new RawValue(s)));
        rootNode.set("responses", arrayNode);
        try {
            return OBJECT_MAPPER.writeValueAsString(rootNode);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to generate JSON.", ex);
        }
    }

    private void addResponses(List<String> responses, Batch<Content> batch) {
        for (Part<Content> part : batch.getParts()) {
            Content partContent = part.getContent();
            if (partContent instanceof Batch) {
                addResponses(responses, (Batch) partContent);
            } else {
                responses.add(partContent.getContent(false));
            }
        }
    }

    @Override
    public Map<String, String> getHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", CONTENT_TYPE_APPLICATION_JSON);
        return headers;
    }
}
