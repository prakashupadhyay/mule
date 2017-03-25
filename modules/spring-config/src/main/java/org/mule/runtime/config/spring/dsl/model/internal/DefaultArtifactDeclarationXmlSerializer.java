/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */
package org.mule.runtime.config.spring.dsl.model.internal;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.mule.runtime.api.i18n.I18nMessageFactory.createStaticMessage;
import static org.mule.runtime.config.spring.dsl.api.xml.SchemaConstants.MULE_SCHEMA_LOCATION;
import static org.mule.runtime.internal.dsl.DslConstants.FLOW_ELEMENT_IDENTIFIER;
import static org.mule.runtime.internal.dsl.DslConstants.NAME_ATTRIBUTE_NAME;
import org.mule.runtime.api.app.declaration.ArtifactDeclaration;
import org.mule.runtime.api.app.declaration.ElementDeclaration;
import org.mule.runtime.api.app.declaration.fluent.ParameterSimpleValue;
import org.mule.runtime.api.dsl.DslResolvingContext;
import org.mule.runtime.api.exception.MuleRuntimeException;
import org.mule.runtime.api.meta.model.XmlDslModel;
import org.mule.runtime.config.spring.dsl.api.ArtifactDeclarationXmlSerializer;
import org.mule.runtime.config.spring.dsl.model.DslElementModelFactory;
import org.mule.runtime.config.spring.dsl.model.XmlArtifactDeclarationLoader;
import org.mule.runtime.config.spring.dsl.model.XmlDslElementModelConverter;

import java.io.InputStream;
import java.io.StringWriter;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Default implementation of {@link ArtifactDeclarationXmlSerializer}
 *
 * @since 4.0
 */
public class DefaultArtifactDeclarationXmlSerializer implements ArtifactDeclarationXmlSerializer {

  private final DslResolvingContext context;

  public DefaultArtifactDeclarationXmlSerializer(DslResolvingContext context) {
    this.context = context;
  }

  @Override
  public String serialize(ArtifactDeclaration declaration) {
    return serializeArtifact(declaration);
  }

  @Override
  public ArtifactDeclaration deserialize(String name, InputStream configResource) {
    return XmlArtifactDeclarationLoader.getDefault(context).load(name, configResource);
  }

  private String serializeArtifact(ArtifactDeclaration artifact) {
    try {
      Document doc = createAppDocument(artifact);

      XmlDslElementModelConverter toXmlConverter = XmlDslElementModelConverter.getDefault(doc);
      DslElementModelFactory modelResolver = DslElementModelFactory.getDefault(context);

      //TODO order
      artifact.getGlobalParameters()
          .forEach(declaration -> appendChildElement(toXmlConverter, doc.getDocumentElement(), modelResolver, declaration));

      artifact.getConfigs()
          .forEach(declaration -> appendChildElement(toXmlConverter, doc.getDocumentElement(), modelResolver, declaration));

      artifact.getFlows()
          .forEach(flowDeclaration -> {
            Element flow = doc.createElement(FLOW_ELEMENT_IDENTIFIER);
            flow.setAttribute(NAME_ATTRIBUTE_NAME, flowDeclaration.getName());

            flowDeclaration.getParameters()
                .stream().filter(p -> p.getValue() instanceof ParameterSimpleValue)
                .forEach(p -> flow.setAttribute(p.getName(), ((ParameterSimpleValue) p.getValue()).getValue()));

            flowDeclaration.getComponents()
                .forEach(declaration -> appendChildElement(toXmlConverter, flow, modelResolver, declaration));

            doc.getDocumentElement().appendChild(flow);
          });

      // write the content into xml file
      TransformerFactory transformerFactory = TransformerFactory.newInstance();

      Transformer transformer = transformerFactory.newTransformer();
      transformer.setOutputProperty(OutputKeys.INDENT, "yes");
      transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

      DOMSource source = new DOMSource(doc);
      StringWriter writer = new StringWriter();
      transformer.transform(source, new StreamResult(writer));
      return writer.getBuffer().toString();

    } catch (Exception e) {
      //TODO
      e.printStackTrace();
      throw new MuleRuntimeException(createStaticMessage("T"
          + "O"
          + "D"
          + "O"), e);
    }
  }

  private Document createAppDocument(ArtifactDeclaration artifact) throws ParserConfigurationException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    DocumentBuilder docBuilder = factory.newDocumentBuilder();

    Document doc = docBuilder.newDocument();
    Element mule = doc.createElement("mule");
    doc.appendChild(mule);

    artifact.getCustomParameters().forEach(p -> mule.setAttribute(p.getName(), p.getValue().toString()));

    mule.setAttributeNS("http://www.w3.org/2000/xmlns/", "xmlns", MULE_SCHEMA_LOCATION);

    if (isBlank(mule.getAttribute("xsi:schemaLocation"))) {
      StringBuilder location = new StringBuilder();
      context.getExtensions().forEach(extension -> {
        XmlDslModel xml = extension.getXmlDslModel();
        location.append(xml.getNamespace()).append(" ").append(xml.getSchemaLocation())
            .append(" ");
      });

      mule.setAttributeNS("http://www.w3.org/2001/XMLSchema-instance",
                          "xsi:schemaLocation", location.toString().trim());
    }
    return doc;
  }

  private void appendChildElement(XmlDslElementModelConverter converter, Element parent, DslElementModelFactory modelResolver,
                                  ElementDeclaration declaration) {
    modelResolver.create(declaration)
        .ifPresent(e -> parent.appendChild(converter.asXml(e)));
  }

}
