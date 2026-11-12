/*
 * Copyright 2002-2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.servlet.view.script;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.context.support.StaticWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.resource.ResourceHandlerUtils;
import org.springframework.web.testfixture.servlet.MockHttpServletRequest;
import org.springframework.web.testfixture.servlet.MockHttpServletResponse;
import org.springframework.web.testfixture.servlet.MockServletContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.InstanceOfAssertFactories.BOOLEAN;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ScriptTemplateView}.
 *
 * @author Sebastien Deleuze
 */
public class ScriptTemplateViewTests {

	private static final String WEBJAR_SCRIPT = "/META-INF/resources/webjars/underscorejs/1.8.3/underscore.js";


	private ScriptTemplateView view;

	private ScriptTemplateConfigurer configurer;

	private StaticWebApplicationContext wac;


	@BeforeEach
	public void setup() {
		this.configurer = new ScriptTemplateConfigurer();
		this.wac = new StaticWebApplicationContext();
		this.wac.getBeanFactory().registerSingleton("scriptTemplateConfigurer", this.configurer);
		this.view = new ScriptTemplateView();
	}


	@Test
	public void missingTemplate() throws Exception {
		MockServletContext servletContext = new MockServletContext();
		this.wac.setServletContext(servletContext);
		this.wac.refresh();
		this.view.setResourceLoaderPath("classpath:org/springframework/web/servlet/view/script/");
		this.view.setUrl("missing.txt");
		this.view.setEngine(mock(InvocableScriptEngine.class));
		this.configurer.setRenderFunction("render");
		this.view.setApplicationContext(this.wac);
		assertThat(this.view.checkResource(Locale.ENGLISH)).isFalse();
	}

	@Test
	public void missingScriptTemplateConfig() {
		assertThatExceptionOfType(ApplicationContextException.class).isThrownBy(() ->
				this.view.setApplicationContext(new StaticApplicationContext()))
			.withMessageContaining("ScriptTemplateConfig");
	}

	@Test
	public void detectScriptTemplateConfigWithEngine() {
		InvocableScriptEngine engine = mock(InvocableScriptEngine.class);
		this.configurer.setEngine(engine);
		this.configurer.setRenderObject("Template");
		this.configurer.setRenderFunction("render");
		this.configurer.setContentType(MediaType.TEXT_PLAIN_VALUE);
		this.configurer.setCharset(StandardCharsets.ISO_8859_1);
		this.configurer.setSharedEngine(true);

		DirectFieldAccessor accessor = new DirectFieldAccessor(this.view);
		this.view.setApplicationContext(this.wac);
		assertThat(accessor.getPropertyValue("engine")).isEqualTo(engine);
		assertThat(accessor.getPropertyValue("renderObject")).isEqualTo("Template");
		assertThat(accessor.getPropertyValue("renderFunction")).isEqualTo("render");
		assertThat(accessor.getPropertyValue("contentType")).isEqualTo(MediaType.TEXT_PLAIN_VALUE);
		assertThat(accessor.getPropertyValue("charset")).isEqualTo(StandardCharsets.ISO_8859_1);
		assertThat(accessor.getPropertyValue("sharedEngine")).asInstanceOf(BOOLEAN).isTrue();
	}

	@Test
	public void detectScriptTemplateConfigWithEngineName() {
		this.configurer.setEngineName("jython");
		this.configurer.setRenderObject("Template");
		this.configurer.setRenderFunction("render");

		DirectFieldAccessor accessor = new DirectFieldAccessor(this.view);
		this.view.setApplicationContext(this.wac);
		assertThat(accessor.getPropertyValue("engineName")).isEqualTo("jython");
		assertThat(accessor.getPropertyValue("engine")).isNotNull();
		assertThat(accessor.getPropertyValue("renderObject")).isEqualTo("Template");
		assertThat(accessor.getPropertyValue("renderFunction")).isEqualTo("render");
		assertThat(accessor.getPropertyValue("contentType")).isEqualTo(MediaType.TEXT_HTML_VALUE);
		assertThat(accessor.getPropertyValue("charset")).isEqualTo(StandardCharsets.UTF_8);
	}

	@Test
	public void customEngineAndRenderFunction() {
		ScriptEngine engine = mock(InvocableScriptEngine.class);
		given(engine.get("key")).willReturn("value");
		this.view.setEngine(engine);
		this.view.setRenderFunction("render");
		this.view.setApplicationContext(this.wac);
		engine = this.view.getEngine();
		assertThat(engine).isNotNull();
		assertThat(engine.get("key")).isEqualTo("value");
		DirectFieldAccessor accessor = new DirectFieldAccessor(this.view);
		assertThat(accessor.getPropertyValue("renderObject")).isNull();
		assertThat(accessor.getPropertyValue("renderFunction")).isEqualTo("render");
		assertThat(accessor.getPropertyValue("charset")).isEqualTo(StandardCharsets.UTF_8);
	}

	@Test
	public void nonSharedEngine() throws Exception {
		int iterations = 20;
		this.view.setEngineName("jython");
		this.view.setRenderFunction("render");
		this.view.setSharedEngine(false);
		this.view.setApplicationContext(this.wac);
		ExecutorService executor = Executors.newFixedThreadPool(4);
		List<Future<Boolean>> results = new ArrayList<>();
		for (int i = 0; i < iterations; i++) {
			results.add(executor.submit(() -> view.getEngine() != null));
		}
		assertThat(results.size()).isEqualTo(iterations);
		for (int i = 0; i < iterations; i++) {
			assertThat((boolean) results.get(i).get()).isTrue();
		}
		executor.shutdown();
	}

	@Test
	public void nonInvocableScriptEngine() {
		this.view.setEngine(mock(ScriptEngine.class));
		this.view.setApplicationContext(this.wac);
	}

	@Test
	public void nonInvocableScriptEngineWithRenderFunction() {
		this.view.setEngine(mock(ScriptEngine.class));
		this.view.setRenderFunction("render");
		assertThatIllegalArgumentException().isThrownBy(() ->
				this.view.setApplicationContext(this.wac));
	}

	@Test
	public void engineAndEngineNameBothDefined() {
		this.view.setEngine(mock(InvocableScriptEngine.class));
		this.view.setEngineName("test");
		this.view.setRenderFunction("render");
		assertThatIllegalArgumentException().isThrownBy(() ->
				this.view.setApplicationContext(this.wac))
			.withMessageContaining("You should define either 'engine', 'engineSupplier', or 'engineName'.");
	}

	@Test  // gh-23258
	public void engineAndEngineSupplierBothDefined() {
		ScriptEngine engine = mock(InvocableScriptEngine.class);
		this.view.setEngineSupplier(() -> engine);
		this.view.setEngine(engine);
		this.view.setRenderFunction("render");
		assertThatIllegalArgumentException().isThrownBy(() ->
				this.view.setApplicationContext(this.wac))
				.withMessageContaining("You should define either 'engine', 'engineSupplier', or 'engineName'.");
	}

	@Test  // gh-23258
	public void engineNameAndEngineSupplierBothDefined() {
		this.view.setEngineSupplier(() -> mock(InvocableScriptEngine.class));
		this.view.setEngineName("test");
		this.view.setRenderFunction("render");
		assertThatIllegalArgumentException().isThrownBy(() ->
				this.view.setApplicationContext(this.wac))
				.withMessageContaining("You should define either 'engine', 'engineSupplier', or 'engineName'.");
	}

	@Test
	public void engineSetterAndNonSharedEngine() {
		this.view.setEngine(mock(InvocableScriptEngine.class));
		this.view.setRenderFunction("render");
		this.view.setSharedEngine(false);
		assertThatIllegalArgumentException().isThrownBy(() ->
				this.view.setApplicationContext(this.wac))
			.withMessageContaining("sharedEngine");
	}

	@Test // SPR-14210
	public void resourceLoaderPath() throws Exception {
		MockServletContext servletContext = new MockServletContext();
		this.wac.setServletContext(servletContext);
		this.wac.refresh();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, this.wac);
		MockHttpServletResponse response = new MockHttpServletResponse();
		Map<String, Object> model = new HashMap<>();
		InvocableScriptEngine engine = mock(InvocableScriptEngine.class);
		given(engine.invokeFunction(any(), any(), any(), any())).willReturn("foo");
		this.view.setEngine(engine);
		this.view.setRenderFunction("render");
		this.view.setApplicationContext(this.wac);
		this.view.setUrl("org/springframework/web/servlet/view/script/empty.txt");
		this.view.render(model, request, response);
		assertThat(response.getContentAsString()).isEqualTo("foo");
		DirectFieldAccessor viewAccessor = new DirectFieldAccessor(this.view);
		String[] resourceLoaderPaths = (String[]) viewAccessor.getPropertyValue("resourceLoaderPaths");
		assertThat(resourceLoaderPaths).containsExactly("classpath:");

		response = new MockHttpServletResponse();
		this.view.setResourceLoaderPath("classpath:org/springframework/web/servlet/view/script/");
		this.view.setUrl("empty.txt");
		this.view.render(model, request, response);
		assertThat(response.getContentAsString()).isEqualTo("foo");
		resourceLoaderPaths = (String[]) viewAccessor.getPropertyValue("resourceLoaderPaths");
		assertThat(resourceLoaderPaths).containsExactly("classpath:org/springframework/web/servlet/view/script/");

		response = new MockHttpServletResponse();
		this.view.setResourceLoaderPath("classpath:org/springframework/web/servlet/view/script");
		this.view.setUrl("empty.txt");
		this.view.render(model, request, response);
		assertThat(response.getContentAsString()).isEqualTo("foo");
		resourceLoaderPaths = (String[]) viewAccessor.getPropertyValue("resourceLoaderPaths");
		assertThat(resourceLoaderPaths).containsExactly("classpath:org/springframework/web/servlet/view/script/");
	}

	@Test  // CVE-2026-22737
	public void getResourceWithRelativeTraversalOutsideResourceLoaderPath() {
		prepareView("classpath:org/springframework/web/servlet/view/script/jython/");

		// Sanity check: a template inside the configured location still resolves.
		assertThat(this.view.getResource("template.html")).isNotNull();

		assertThat(this.view.getResource("../empty.txt")).isNull();
		assertThat(this.view.getResource("..\\empty.txt")).isNull();
		assertThat(this.view.getResource("./../empty.txt")).isNull();
		assertThat(this.view.getResource("nested/../../empty.txt")).isNull();
		assertThat(this.view.getResource("../../../../../../../../etc/passwd")).isNull();
	}

	@Test  // CVE-2026-22737
	public void getResourceWithAbsoluteLocationOutsideResourceLoaderPath() {
		prepareView("classpath:org/springframework/web/servlet/view/script/jython/");

		assertThat(this.view.getResource("/org/springframework/web/servlet/view/script/empty.txt")).isNull();
		assertThat(this.view.getResource("classpath:org/springframework/web/servlet/view/script/empty.txt")).isNull();
		assertThat(this.view.getResource("//org/springframework/web/servlet/view/script/empty.txt")).isNull();
	}

	@Test  // CVE-2026-22737
	public void getResourceWithUrlLocationOutsideResourceLoaderPath(@TempDir Path tempDir) throws Exception {
		Path secret = tempDir.resolve("secret.txt");
		Files.write(secret, "secret".getBytes(StandardCharsets.UTF_8));
		prepareView("classpath:org/springframework/web/servlet/view/script/");

		assertThat(this.view.getResource(secret.toUri().toString())).isNull();
		assertThat(this.view.getResource("file:" + secret.toUri().getPath())).isNull();
		assertThat(this.view.getResource("url:" + secret.toUri())).isNull();
	}

	@Test  // CVE-2026-22737
	public void getResourceWithEncodedTraversalOutsideResourceLoaderPath(@TempDir Path tempDir) throws Exception {
		Path templates = Files.createDirectories(tempDir.resolve("templates"));
		Files.write(templates.resolve("template.txt"), "template".getBytes(StandardCharsets.UTF_8));
		Files.write(tempDir.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));
		prepareView(templates.toUri().toString());

		// Sanity check: a template inside the configured location still resolves.
		assertThat(this.view.getResource("template.txt")).isNotNull();

		assertThat(this.view.getResource("../secret.txt")).isNull();
		assertThat(this.view.getResource("%2E%2E/secret.txt")).isNull();
		assertThat(this.view.getResource("%2e%2e%2Fsecret.txt")).isNull();
		assertThat(this.view.getResource("%252E%252E/secret.txt")).isNull();
		assertThat(this.view.getResource("..%2Fsecret.txt")).isNull();
	}

	@Test  // CVE-2026-22737
	public void resourceOutsideResourceLoaderPathLocationIsRejected(@TempDir Path tempDir) throws Exception {
		Path templates = Files.createDirectories(tempDir.resolve("templates"));
		Files.write(templates.resolve("template.txt"), "template".getBytes(StandardCharsets.UTF_8));
		Files.write(tempDir.resolve("secret.txt"), "secret".getBytes(StandardCharsets.UTF_8));

		Resource location = this.wac.getResource(templates.toUri().toString());
		Resource inside = this.wac.getResource(templates.toUri() + "template.txt");
		Resource outside = this.wac.getResource(tempDir.resolve("secret.txt").toUri().toString());
		Resource encodedTraversal = this.wac.getResource(templates.toUri() + "%2E%2E/secret.txt");

		assertThat(ResourceHandlerUtils.isResourceUnderLocation(location, inside)).isTrue();
		assertThat(ResourceHandlerUtils.isResourceUnderLocation(location, outside)).isFalse();
		assertThat(ResourceHandlerUtils.isResourceUnderLocation(location, encodedTraversal)).isFalse();
	}

	@Test  // CVE-2026-22737
	public void getResourceWithBlankOrProtectedLocation() {
		prepareView("classpath:org/springframework/web/servlet/view/script/");

		assertThat(this.view.getResource("")).isNull();
		assertThat(this.view.getResource("   ")).isNull();
		assertThat(this.view.getResource("../../../../META-INF/MANIFEST.MF")).isNull();
		assertThat(this.view.getResource("WEB-INF/web.xml")).isNull();
	}

	@Test  // CVE-2026-22737
	public void loadScriptsFromWebJarLocation() throws Exception {
		InvocableScriptEngine engine = mock(InvocableScriptEngine.class);
		this.wac.setServletContext(new MockServletContext());
		this.wac.refresh();
		this.configurer.setEngine(engine);
		this.configurer.setScripts(WEBJAR_SCRIPT);
		this.configurer.setResourceLoaderPath("classpath:org/springframework/web/servlet/view/script/");

		// Scripts configured by the application are loaded the same way as before
		this.view.setApplicationContext(this.wac);
		verify(engine).eval(any(Reader.class));
		assertThat(this.view.getScriptResource(WEBJAR_SCRIPT)).isNotNull();
		assertThat(this.view.getScriptResource("jython/render.py")).isNotNull();

		// ...while template lookups for the same location stay hardened
		assertThat(this.view.getResource(WEBJAR_SCRIPT)).isNull();
	}

	@Test  // CVE-2026-22737
	public void loadScriptsFromWebJarLocationWithDefaultResourceLoaderPath() throws Exception {
		InvocableScriptEngine engine = mock(InvocableScriptEngine.class);
		this.wac.setServletContext(new MockServletContext());
		this.wac.refresh();
		this.configurer.setEngine(engine);
		this.configurer.setScripts(WEBJAR_SCRIPT, "org/springframework/web/servlet/view/script/jython/render.py");

		this.view.setApplicationContext(this.wac);
		verify(engine, times(2)).eval(any(Reader.class));
	}

	@Test // SPR-13379
	public void contentType() throws Exception {
		MockServletContext servletContext = new MockServletContext();
		this.wac.setServletContext(servletContext);
		this.wac.refresh();
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setAttribute(DispatcherServlet.WEB_APPLICATION_CONTEXT_ATTRIBUTE, this.wac);
		MockHttpServletResponse response = new MockHttpServletResponse();
		Map<String, Object> model = new HashMap<>();
		this.view.setEngine(mock(InvocableScriptEngine.class));
		this.view.setRenderFunction("render");
		this.view.setResourceLoaderPath("classpath:org/springframework/web/servlet/view/script/");
		this.view.setUrl("empty.txt");
		this.view.setApplicationContext(this.wac);

		this.view.render(model, request, response);
		assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo((MediaType.TEXT_HTML_VALUE + ";charset=" +
				StandardCharsets.UTF_8));

		response = new MockHttpServletResponse();
		this.view.setContentType(MediaType.TEXT_PLAIN_VALUE);
		this.view.render(model, request, response);
		assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo((MediaType.TEXT_PLAIN_VALUE + ";charset=" +
				StandardCharsets.UTF_8));

		response = new MockHttpServletResponse();
		this.view.setCharset(StandardCharsets.ISO_8859_1);
		this.view.render(model, request, response);
		assertThat(response.getHeader(HttpHeaders.CONTENT_TYPE)).isEqualTo((MediaType.TEXT_PLAIN_VALUE + ";charset=" +
				StandardCharsets.ISO_8859_1));

	}

	@Test  // gh-23258
	public void engineSupplierWithSharedEngine() {
		this.configurer.setEngineSupplier(() -> mock(InvocableScriptEngine.class));
		this.configurer.setRenderObject("Template");
		this.configurer.setRenderFunction("render");
		this.configurer.setSharedEngine(true);

		DirectFieldAccessor accessor = new DirectFieldAccessor(this.view);
		this.view.setApplicationContext(this.wac);
		ScriptEngine engine1 = this.view.getEngine();
		ScriptEngine engine2 = this.view.getEngine();
		assertThat(engine1).isNotNull();
		assertThat(engine2).isNotNull();
		assertThat(accessor.getPropertyValue("renderObject")).isEqualTo("Template");
		assertThat(accessor.getPropertyValue("renderFunction")).isEqualTo("render");
		assertThat(accessor.getPropertyValue("sharedEngine")).asInstanceOf(BOOLEAN).isTrue();
	}

	@Test  // gh-23258
	public void engineSupplierWithNonSharedEngine() {
		this.configurer.setEngineSupplier(() -> mock(InvocableScriptEngine.class));
		this.configurer.setRenderObject("Template");
		this.configurer.setRenderFunction("render");
		this.configurer.setSharedEngine(false);

		DirectFieldAccessor accessor = new DirectFieldAccessor(this.view);
		this.view.setApplicationContext(this.wac);
		ScriptEngine engine1 = this.view.getEngine();
		ScriptEngine engine2 = this.view.getEngine();
		assertThat(engine1).isNotNull();
		assertThat(engine2).isNotNull();
		assertThat(accessor.getPropertyValue("renderObject")).isEqualTo("Template");
		assertThat(accessor.getPropertyValue("renderFunction")).isEqualTo("render");
		assertThat(accessor.getPropertyValue("sharedEngine")).asInstanceOf(BOOLEAN).isFalse();
	}

	private void prepareView(String resourceLoaderPath) {
		this.wac.setServletContext(new MockServletContext());
		this.wac.refresh();
		this.view.setResourceLoaderPath(resourceLoaderPath);
		this.view.setEngine(mock(InvocableScriptEngine.class));
		this.view.setApplicationContext(this.wac);
	}


	private interface InvocableScriptEngine extends ScriptEngine, Invocable {
	}

}
