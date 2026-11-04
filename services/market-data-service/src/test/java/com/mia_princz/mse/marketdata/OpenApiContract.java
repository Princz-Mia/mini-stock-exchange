package com.mia_princz.mse.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The OpenAPI contract in {@code api/openapi.yaml}, used to check this service against it: each real response body
 * must match the schema the contract gives for its operation, status and content type, and every route the service
 * serves must be in the contract under its tag, and the other way round.
 */
final class OpenApiContract {

	private static final Path FILE = Path.of("../../api/openapi.yaml");

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final OpenApiContract INSTANCE = new OpenApiContract();

	private final JsonNode document;

	private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

	private OpenApiContract() {
		this.document = YAMLMapper.builder().build().readTree(FILE.toAbsolutePath().normalize().toFile());
	}

	static OpenApiContract get() {
		return INSTANCE;
	}

	/**
	 * Fails unless the contract lists this status for the operation and the body matches its schema. A null body
	 * checks the content type only, for a stream whose events are checked one by one with {@link #assertSchema}.
	 */
	void assertResponse(String method, String pathTemplate, int status, String contentType, String body) {
		String where = method + " " + pathTemplate + " " + status;
		String responsePointer = "/paths/" + escape(pathTemplate) + "/" + method.toLowerCase() + "/responses/" + status;
		JsonNode response = document.at(responsePointer);
		assertThat(response.isMissingNode()).as(where + " is not in the contract").isFalse();
		if (response.has("$ref")) {
			responsePointer = response.get("$ref").asString().substring(1);
			response = document.at(responsePointer);
		}
		if (!response.has("content")) {
			assertThat(body).as(where + " has no body in the contract").isNullOrEmpty();
			return;
		}
		if ((body == null || body.isEmpty()) && response.path("x-may-be-empty").asBoolean(false)) {
			return;
		}
		String type = contentType == null ? "" : contentType.split(";")[0].trim();
		assertThat(response.get("content").has(type)).as(where + " does not allow " + type).isTrue();
		if (body != null) {
			assertValid(responsePointer + "/content/" + escape(type) + "/schema", body, where);
		}
	}

	/** Fails unless the JSON matches a named schema, such as the data of a server-sent event. */
	void assertSchema(String component, String json) {
		assertValid("/components/schemas/" + component, json, component);
	}

	/** The operations under a tag, as {@code METHOD /path/{}} with path variables blanked. */
	Set<String> operations(String tag) {
		Set<String> operations = new TreeSet<>();
		for (Map.Entry<String, JsonNode> path : document.get("paths").properties()) {
			for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
				for (JsonNode operationTag : operation.getValue().path("tags")) {
					if (operationTag.asString().equals(tag)) {
						operations.add(operation.getKey().toUpperCase() + " " + blankVariables(path.getKey()));
					}
				}
			}
		}
		return operations;
	}

	static String blankVariables(String path) {
		return path.replaceAll("\\{[^}]+}", "{}");
	}

	private void assertValid(String schemaPointer, String json, String where) {
		ObjectNode root = (ObjectNode) document.deepCopy();
		root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
		root.put("$ref", "#" + schemaPointer);
		Schema schema = registry.getSchema(SchemaLocation.of("urn:mse:openapi"), root);
		List<Error> errors = schema.validate(JSON.readTree(json),
				(context) -> context.executionConfig((config) -> config.formatAssertionsEnabled(true)));
		assertThat(errors).as(where + " does not match the contract: " + json).isEmpty();
	}

	/** JSON Pointer escaping for a key such as {@code /api/v1/orders} or {@code application/json}. */
	private static String escape(String key) {
		return key.replace("~", "~0").replace("/", "~1");
	}

}
