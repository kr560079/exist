/*
 * eXist-db Open Source Native XML Database
 * Copyright (C) 2001 The eXist-db Authors
 *
 * info@exist-db.org
 * http://www.exist-db.org
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.collections;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.exist.collections.triggers.CollectionTrigger;
import org.exist.collections.triggers.CollectionTriggerProxy;
import org.exist.collections.triggers.DocumentTrigger;
import org.exist.collections.triggers.DocumentTriggerProxy;
import org.exist.collections.triggers.Trigger;
import org.exist.collections.triggers.TriggerException;
import org.exist.collections.triggers.TriggerProxy;
import org.exist.config.annotation.ConfigurationClass;
import org.exist.dom.persistent.DocumentImpl;
import org.exist.indexing.IndexWorker;
import org.exist.security.Account;
import org.exist.security.AbstractAccount;
import org.exist.security.Subject;
import org.exist.security.internal.SubjectAccreditedImpl;
import org.exist.xquery.schema.TypeAnnotationProvider;
import org.exist.storage.BrokerPool;
import org.exist.storage.DBBroker;
import org.exist.storage.IndexSpec;
import org.exist.util.DatabaseConfigurationException;
import org.exist.util.ParametersExtractor;
import org.exist.util.SchemaVersion;
import org.exist.util.XMLReaderObjectFactory;
import org.exist.xmldb.XmldbURI;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@ConfigurationClass("collection")
public class CollectionConfiguration {

    public final static String COLLECTION_CONFIG_SUFFIX = ".xconf";
    public final static XmldbURI COLLECTION_CONFIG_SUFFIX_URI = XmldbURI.create(COLLECTION_CONFIG_SUFFIX);
    public final static String DEFAULT_COLLECTION_CONFIG_FILE = "collection" + COLLECTION_CONFIG_SUFFIX;
    public final static XmldbURI DEFAULT_COLLECTION_CONFIG_FILE_URI = XmldbURI.create(DEFAULT_COLLECTION_CONFIG_FILE);

    public final static String NAMESPACE = "http://exist-db.org/collection-config/1.0";

    private static final String ROOT_ELEMENT = "collection";

    /**
     * First level element in a collection configuration document
     */
    private static final String TRIGGERS_ELEMENT = "triggers";
    private static final String TRIGGER_ELEMENT = "trigger";
    private static final String CLASS_ATTRIBUTE = "class";
    private static final String PARAMETER_ELEMENT = "parameter";

    /**
     * First level element in a collection configuration document
     */
    private static final String INDEX_ELEMENT = "index";
    private static final String SCHEMA_TYPE_ELEMENT = "schema-type";
    private static final String GROUP_ELEMENT = "default-group";
    private static final String RESOURCE_ATTR = "resource";
    private static final String COLLECTION_ATTR = "collection";

    private static final String VALIDATION_ELEMENT = "validation";
    private static final String VALIDATION_MODE_ATTR = "mode";

    private static final Logger LOG = LogManager.getLogger(CollectionConfiguration.class);

    private final List<TriggerProxy<? extends CollectionTrigger>> colTriggers = new ArrayList<>();
    private final List<TriggerProxy<? extends DocumentTrigger>> docTriggers = new ArrayList<>();

    private IndexSpec indexSpec = null;

    private XmldbURI docName = null;
    private XmldbURI srcCollectionURI;

    private XMLReaderObjectFactory.VALIDATION_SETTING validationMode = XMLReaderObjectFactory.VALIDATION_SETTING.UNKNOWN;

    private final BrokerPool pool;

    public CollectionConfiguration(final BrokerPool pool) {
        this.pool = pool;
    }

    /**
     * @param broker the database broker
     * @param doc collection configuration document
     * @param checkOnly true to only check
     * @param srcCollectionURI The collection from which the document is being read.  This
     *                         is not necessarily the same as this.collection.getURI() because the
     *                         source document may have come from a parent collection.
     * @param docName The name of the document being read

     * @throws CollectionConfigurationException if an error occurs whilst reading the collection configuration
     */
    protected void read(final DBBroker broker, final Document doc, final boolean checkOnly,
            final XmldbURI srcCollectionURI, final XmldbURI docName) throws CollectionConfigurationException {
        if (!checkOnly) {
            this.docName = docName;
            this.srcCollectionURI = srcCollectionURI;
        }
        final Element root = doc.getDocumentElement();
        if (root == null) {
            throwOrLog("Configuration document can not be parsed", checkOnly);
            return;
        }
        if (!ROOT_ELEMENT.equals(root.getLocalName())) {
            throwOrLog("Expected element '" + ROOT_ELEMENT +
                    "' in configuration document. Got element '" + root.getLocalName() + "'", checkOnly);
            return;
        }
        if (root.getNamespaceURI() == null || !NAMESPACE.equals(root.getNamespaceURI())) {
            throwOrLog("Expected namespace '" + NAMESPACE +
                    "' for element '" + PARAMETER_ELEMENT +
                    "' in configuration document. Got '" + root.getNamespaceURI() + "'", checkOnly);
            return;
        }
        SchemaVersion.logDocumentVersion(LOG, root, SchemaVersion.COLLECTION_XCONF,
                "collection.xconf" + (docName != null ? " (" + docName + ")" : ""));
        final NodeList childNodes = root.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++) {
            Node node = childNodes.item(i);
            if (NAMESPACE.equals(node.getNamespaceURI())) {
                switch (node.getLocalName()) {
                    case TRIGGERS_ELEMENT -> {
                        final NodeList triggers = node.getChildNodes();
                        for (int j = 0; j < triggers.getLength(); j++) {
                            node = triggers.item(j);
                            if (node.getNodeType() == Node.ELEMENT_NODE && node.getLocalName().equals(TRIGGER_ELEMENT)) {
                                configureTrigger(broker.getBrokerPool().getClassLoader(), (Element) node, srcCollectionURI, checkOnly);
                            }
                        }
                    }
                    case INDEX_ELEMENT -> {
                        final Element elem = (Element) node;
                        // a schema-type section reads database documents: do that as the owner of the
                        // configuration document, not as whoever happens to trigger the (re)load
                        final Subject asOwner = hasSchemaType(elem) ? ownerSubject(broker, doc) : null;
                        if (asOwner != null) {
                            broker.pushSubject(asOwner);
                        }
                        try {
                            if (checkOnly && hasSchemaType(elem)
                                    && broker.getIndexController().getWorkerByIndexId(TypeAnnotationProvider.INDEX_ID) instanceof TypeAnnotationProvider provider) {
                                // checking a configuration is strict, unlike loading a stored one
                                checkSchemaTypeSection(broker, elem, provider, null);
                            }
                            if (indexSpec == null) {
                                indexSpec = new IndexSpec(broker, elem);
                            } else {
                                indexSpec.read(broker, elem);
                            }
                        } catch (final DatabaseConfigurationException e) {
                            if (checkOnly) {
                                throw new CollectionConfigurationException(e.getMessage(), e);
                            } else {
                                LOG.warn(e.getMessage(), e);
                            }
                        } finally {
                            if (asOwner != null) {
                                broker.popSubject();
                            }
                        }

                    }
                    case VALIDATION_ELEMENT -> {
                        final Element elem = (Element) node;
                        final String mode = elem.getAttribute(VALIDATION_MODE_ATTR);
                        if (mode.isEmpty()) {
                            LOG.debug("Unable to determine validation mode in {}", srcCollectionURI);
                            validationMode = XMLReaderObjectFactory.VALIDATION_SETTING.UNKNOWN;
                        } else {
                            LOG.debug("{} : Validation mode={}", srcCollectionURI, mode);
                            validationMode = XMLReaderObjectFactory.VALIDATION_SETTING.fromOption(mode);
                        }

                    }
                    case null, default -> throwOrLog("Ignored node '" + node.getLocalName() +
                            "' in configuration document", checkOnly);

                    //TODO : throw an exception like above ? -pb
                }

            } else if (node.getNodeType() == Node.ELEMENT_NODE) {
                throwOrLog("Ignored node '" + node.getLocalName() + "' in namespace '" +
                        node.getNamespaceURI() + "' in configuration document", checkOnly);
            }
        }
    }

    private static boolean hasSchemaType(final Element index) {
        return childElements(index, SCHEMA_TYPE_ELEMENT).stream().findAny().isPresent();
    }

    private static List<Element> childElements(final Element parent, final String... localNames) {
        final List<Element> result = new ArrayList<>();
        final NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            final Node kid = kids.item(i);
            if (kid.getNodeType() == Node.ELEMENT_NODE && NAMESPACE.equals(kid.getNamespaceURI())) {
                for (final String name : localNames) {
                    if (name.equals(kid.getLocalName())) {
                        result.add((Element) kid);
                        break;
                    }
                }
            }
        }
        return result;
    }

    /**
     * @return a subject for the owner of the configuration document, or null if the document is not a
     *     stored document (or its owner cannot be determined) and the ambient subject should be used
     */
    private static @Nullable Subject ownerSubject(final DBBroker broker, final Document doc) {
        if (!(doc instanceof DocumentImpl stored)) {
            return null;
        }
        return subjectFor(broker, stored.getPermissions().getOwner());
    }

    private static @Nullable Subject subjectFor(final DBBroker broker, @Nullable final Account owner) {
        final org.exist.security.SecurityManager sm = broker.getBrokerPool().getSecurityManager();
        if (owner == null) {
            return null;
        }
        if (owner.getName().equals(sm.getSystemSubject().getName())) {
            return sm.getSystemSubject();
        }
        final Account account = sm.getAccount(owner.getName());
        if (account instanceof AbstractAccount abstractAccount) {
            return new SubjectAccreditedImpl(abstractAccount, sm);
        }
        return null;
    }

    /**
     * Called when a collection configuration document is stored: configures only its
     * {@code schema-type} sections (as the document's owner) so that an unusable schema rejects the
     * store. Every other index section keeps its warn-only behaviour.
     *
     * @param broker the broker
     * @param doc the stored configuration document
     * @throws CollectionConfigurationException if a schema-type section is invalid
     */
    public static void checkSchemaTypeSections(final DBBroker broker, final Document doc) throws CollectionConfigurationException {
        checkSchemaTypeSections(broker, doc, ownerSubject(broker, doc));
    }

    /**
     * As {@link #checkSchemaTypeSections(DBBroker, Document)}, for a document that is not stored yet.
     *
     * @param owner the account that will own the stored document: the schema is read with its permissions
     */
    public static void checkSchemaTypeSections(final DBBroker broker, final Document doc, @Nullable final Account owner) throws CollectionConfigurationException {
        checkSchemaTypeSections(broker, doc, subjectFor(broker, owner));
    }

    private static void checkSchemaTypeSections(final DBBroker broker, final Document doc, @Nullable final Subject asOwner) throws CollectionConfigurationException {
        final Element root = doc.getDocumentElement();
        if (root == null || !NAMESPACE.equals(root.getNamespaceURI())) {
            return;
        }
        final IndexWorker worker = broker.getIndexController().getWorkerByIndexId(TypeAnnotationProvider.INDEX_ID);
        if (!(worker instanceof TypeAnnotationProvider provider)) {
            return;  // the schema-type index module is not enabled
        }
        for (final Element index : childElements(root, INDEX_ELEMENT)) {
            checkSchemaTypeSection(broker, index, provider, asOwner);
        }
    }

    /** strictly configure the schema-type section of an {@code index} element, if it has one */
    private static void checkSchemaTypeSection(final DBBroker broker, final Element index,
            final TypeAnnotationProvider provider, @Nullable final Subject asOwner) throws CollectionConfigurationException {
        final List<Element> sections = childElements(index, SCHEMA_TYPE_ELEMENT, "typed-values");
        if (sections.stream().noneMatch(e -> SCHEMA_TYPE_ELEMENT.equals(e.getLocalName()))) {
            return;
        }
        final NodeList nodes = new NodeList() {
            @Override
            public Node item(final int i) {
                return sections.get(i);
            }

            @Override
            public int getLength() {
                return sections.size();
            }
        };
        if (asOwner != null) {
            broker.pushSubject(asOwner);
        }
        try {
            provider.checkConfiguration(broker.getIndexController(), nodes, IndexSpec.getNamespaceMap(index));
        } catch (final DatabaseConfigurationException e) {
            throw new CollectionConfigurationException(e.getMessage(), e);
        } finally {
            if (asOwner != null) {
                broker.popSubject();
            }
        }
    }

    private void throwOrLog(final String message, final boolean throwExceptions) throws CollectionConfigurationException {
        if (throwExceptions) {
            throw new CollectionConfigurationException(message);
        } else {
            LOG.warn(message);
        }
    }

    public XmldbURI getDocName() {
        return docName;
    }

    protected void setIndexConfiguration(final IndexSpec spec) {
        this.indexSpec = spec;
    }

    public XmldbURI getSourceCollectionURI() {
        return srcCollectionURI;
    }

    public XMLReaderObjectFactory.VALIDATION_SETTING getValidationMode() {
        return validationMode;
    }

    public IndexSpec getIndexConfiguration() {
        return indexSpec;
    }

    private void configureTrigger(final ClassLoader cl, final Element triggerElement, final XmldbURI collectionConfigurationURI, final boolean testOnly) throws CollectionConfigurationException {

        //TODO : rely on schema-driven validation -pb

        final String classname = triggerElement.getAttributes().getNamedItem(CLASS_ATTRIBUTE).getNodeValue();

        try {
            final Class clazz = Class.forName(classname, true, cl);
            if (!Trigger.class.isAssignableFrom(clazz)) {
                throwOrLog("Trigger's class '" + classname + "' is not assignable from '" + Trigger.class + "'", testOnly);
                return;
            }
            final NodeList nlParameter = triggerElement.getElementsByTagNameNS(NAMESPACE, PARAMETER_ELEMENT);
            final Map<String, List<? extends Object>> parameters = ParametersExtractor.extract(nlParameter);

            boolean added = false;
            if (DocumentTrigger.class.isAssignableFrom(clazz)) {
                docTriggers.add(new DocumentTriggerProxy((Class<? extends DocumentTrigger>) clazz, parameters));
                added = true;
            }

            if (CollectionTrigger.class.isAssignableFrom(clazz)) {
                colTriggers.add(new CollectionTriggerProxy((Class<? extends CollectionTrigger>) clazz, parameters));
                added = true;
            }

            if (!added) {
                throw new TriggerException("Unknown Trigger class type: " + clazz.getName());
            }

        } catch (final ClassNotFoundException | TriggerException e) {
            if (testOnly) {
                throw new CollectionConfigurationException(e.getMessage(), e);
            } else {
                LOG.warn("Trigger class not found: {}", e.getMessage(), e);
            }
        }
    }

    public List<TriggerProxy<? extends CollectionTrigger>> collectionTriggers() {
        return colTriggers;
    }

    public List<TriggerProxy<? extends DocumentTrigger>> documentTriggers() {
        return docTriggers;
    }

    public boolean triggerRegistered(final Class<?> triggerClass) {
        if(DocumentTrigger.class.isAssignableFrom(triggerClass)) {
            if(hasTriggerProxy(docTriggers, (Class<? extends DocumentTrigger>)triggerClass)) {
                return true;
            }
        }

        if(CollectionTrigger.class.isAssignableFrom(triggerClass)) {
            if(hasTriggerProxy(colTriggers, (Class<? extends CollectionTrigger>)triggerClass)) {
                return true;
            }
        }

        return false;
    }

    private <T> boolean hasTriggerProxy(final List<TriggerProxy<? extends T>> triggerProxies, final Class<? extends T> triggerProxyClazz) {
        for(final TriggerProxy<? extends T> triggerProxy : triggerProxies) {
            if(triggerProxy.getClazz() == triggerProxyClazz) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        final StringBuilder result = new StringBuilder();
        if (indexSpec != null) {
            result.append(indexSpec).append(System.getProperty("line.separator"));
        }
        return result.toString();
    }
}
