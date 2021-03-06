package com.logicaldoc.web.data;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;

import com.ibm.icu.util.StringTokenizer;
import com.logicaldoc.core.document.AbstractDocument;
import com.logicaldoc.core.document.Bookmark;
import com.logicaldoc.core.document.Document;
import com.logicaldoc.core.document.DocumentComparator;
import com.logicaldoc.core.document.dao.DocumentDAO;
import com.logicaldoc.core.folder.Folder;
import com.logicaldoc.core.folder.FolderDAO;
import com.logicaldoc.core.metadata.Attribute;
import com.logicaldoc.core.security.Session;
import com.logicaldoc.core.security.User;
import com.logicaldoc.core.security.dao.UserDAO;
import com.logicaldoc.i18n.I18N;
import com.logicaldoc.util.Context;
import com.logicaldoc.util.LocaleUtil;
import com.logicaldoc.util.config.ContextProperties;
import com.logicaldoc.web.util.ServiceUtil;

/**
 * This servlet is responsible for documents data.
 * 
 * @author Marco Meschieri - Logical Objects
 * @since 6.0
 */
public class DocumentsDataServlet extends HttpServlet {

	private static final long serialVersionUID = 1L;

	private static Logger log = LoggerFactory.getLogger(DocumentsDataServlet.class);

	@Override
	protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException,
			IOException {
		try {
			Context context = Context.get();
			Session session = ServiceUtil.validateSession(request);
			ContextProperties config = Context.get().getProperties();
			UserDAO udao = (UserDAO) context.getBean(UserDAO.class);
			User user = udao.findById(session.getUserId());
			udao.initialize(user);

			String locale = request.getParameter("locale");
			if (StringUtils.isEmpty(locale))
				locale = user.getLanguage();

			String sort = request.getParameter("sort");

			response.setContentType("text/xml");
			response.setCharacterEncoding("UTF-8");

			// Avoid resource caching
			response.setHeader("Pragma", "no-cache");
			response.setHeader("Cache-Control", "no-store");
			response.setDateHeader("Expires", 0);

			FolderDAO fDao = (FolderDAO) context.getBean(FolderDAO.class);
			DocumentDAO dao = (DocumentDAO) context.getBean(DocumentDAO.class);
			DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
			df.setTimeZone(TimeZone.getTimeZone("UTC"));

			int max = 100;
			if (StringUtils.isNotEmpty(request.getParameter("max")))
				max = Integer.parseInt(request.getParameter("max"));

			int page = 1;
			if (StringUtils.isNotEmpty(request.getParameter("page")))
				page = Integer.parseInt(request.getParameter("page"));

			Integer status = null;
			if (StringUtils.isNotEmpty(request.getParameter("status")))
				status = Integer.parseInt(request.getParameter("status"));

			PrintWriter writer = response.getWriter();
			writer.write("<list>");

			String sql = "select ld_docid from ld_bookmark where ld_type=" + Bookmark.TYPE_DOCUMENT
					+ " and ld_deleted = 0 and ld_userid = " + session.getUserId();
			@SuppressWarnings("unchecked")
			List<Long> bookmarks = (List<Long>) dao.queryForList(sql, Long.class);

			// The list of documents to be returned
			List<Document> documentRecords = new ArrayList<Document>();

			/*
			 * Retrieve the names of the extended attributes to show
			 */
			String extattrs = config.getProperty(session.getTenantName() + ".search.extattr");
			List<String> attrs = new ArrayList<String>();
			if (StringUtils.isNotEmpty(extattrs)) {
				StringTokenizer st = new StringTokenizer(extattrs.trim(), ",;");
				while (st.hasMoreElements())
					attrs.add(st.nextToken().trim());
			}

			/*
			 * Contains the extended attributes of the documents. The key is
			 * documentId-atttributeName, the value is the attribute value. This
			 * fieldsMap is used to maximize the listing performances.
			 */
			final Map<String, String> extValues = new HashMap<String, String>();

			if (status != null && status.intValue() != AbstractDocument.DOC_ARCHIVED) {
				List<Document> docs = dao.findByLockUserAndStatus(session.getUserId(), status);
				int begin = (page - 1) * max;
				int end = Math.min(begin + max - 1, docs.size() - 1);
				for (int i = begin; i <= end; i++) {
					Document doc = docs.get(i);

					if (!fDao.isReadEnabled(doc.getFolder().getId(), session.getUserId()))
						continue;

					documentRecords.add(doc);
				}
			} else if (StringUtils.isNotEmpty(request.getParameter("docIds"))) {
				String[] idsArray = request.getParameter("docIds").split(",");
				for (String id : idsArray) {
					Document doc = dao.findById(Long.parseLong(id));
					if (doc == null || doc.getDeleted() == 1)
						continue;

					if (!fDao.isReadEnabled(doc.getFolder().getId(), session.getUserId()))
						continue;

					documentRecords.add(doc);
				}
			} else {
				/*
				 * Load some filters from the current request
				 */
				Long folderId = null;
				if (StringUtils.isNotEmpty(request.getParameter("folderId")))
					folderId = new Long(request.getParameter("folderId"));
				if (folderId != null && session != null && !fDao.isReadEnabled(folderId, session.getUserId()))
					throw new Exception("Folder " + folderId + " is not accessible by user " + session.getUsername());

				String filename = null;
				if (StringUtils.isNotEmpty(request.getParameter("filename")))
					filename = request.getParameter("filename");

				if (!attrs.isEmpty()) {
					log.debug("Search for extended attributes " + extattrs);

					StringBuffer query = new StringBuffer(
							"select ld_docid, ld_name, ld_type, ld_stringvalue, ld_intvalue, ld_doublevalue, ld_datevalue ");
					query.append(" from ld_document_ext where ld_docid in (");
					query.append("select D.ld_id from ld_document D where D.ld_deleted=0 ");
					if (folderId != null)
						query.append(" and D.ld_folderid=" + Long.toString(folderId));
					query.append(") and ld_name in ");
					query.append(attrs.toString().replaceAll("\\[", "('").replaceAll("\\]", "')")
							.replaceAll(",", "','").replaceAll(" ", ""));

					final Locale l = LocaleUtil.toLocale(locale);
					final SimpleDateFormat edf = new SimpleDateFormat(I18N.message("format_dateshort", l));

					dao.query(query.toString(), null, new RowMapper<Long>() {
						@Override
						public Long mapRow(ResultSet rs, int row) throws SQLException {
							Long docId = rs.getLong(1);
							String name = rs.getString(2);
							int type = rs.getInt(3);

							String key = docId + "-" + name;

							if (type == Attribute.TYPE_STRING) {
								extValues.put(key, rs.getString(4));
							} else if (type == Attribute.TYPE_INT) {
								extValues.put(key, Long.toString(rs.getLong(5)));
							} else if (type == Attribute.TYPE_DOUBLE) {
								extValues.put(key, Double.toString(rs.getDouble(6)));
							} else if (type == Attribute.TYPE_DATE) {
								extValues.put(key, rs.getDate(7) != null ? edf.format(rs.getDate(7)) : "");
							} else if (type == Attribute.TYPE_USER) {
								extValues.put(key, rs.getString(4));
							} else if (type == Attribute.TYPE_BOOLEAN) {
								extValues.put(key,
										rs.getLong(5) == 1L ? I18N.message("true", l) : I18N.message("false", l));
							}

							return null;
						}
					}, null);
				}

				/*
				 * Execute the Query
				 */
				StringBuffer query = new StringBuffer(
						"select A.id, A.customId, A.docRef, A.type, A.version, A.lastModified, A.date, A.publisher,"
								+ " A.creation, A.creator, A.fileSize, A.immutable, A.indexed, A.lockUserId, A.fileName, A.status,"
								+ " A.signed, A.type, A.rating, A.fileVersion, A.comment, A.workflowStatus,"
								+ " A.startPublishing, A.stopPublishing, A.published, A.extResId,"
								+ " B.name, A.docRefType, A.stamped, A.lockUser, A.password, A.pages "
								+ " from Document as A left outer join A.template as B ");
				query.append(" where A.deleted = 0 and not A.status=" + AbstractDocument.DOC_ARCHIVED);
				if (folderId != null)
					query.append(" and A.folder.id=" + folderId);
				if (StringUtils.isNotEmpty(request.getParameter("indexed")))
					query.append(" and A.indexed=" + request.getParameter("indexed"));
				if (filename != null)
					query.append(" and lower(A.fileName) like '%" + filename.toLowerCase() + "%' ");

				if (StringUtils.isEmpty(sort))
					query.append(" order by A.lastModified desc");

				List<Object> records = (List<Object>) dao.findByQuery(query.toString(), null, null);
				List<Document> documents = new ArrayList<Document>();

				/*
				 * Iterate over records enriching the data
				 */
				for (int i = 0; i < records.size(); i++) {
					Object[] cols = (Object[]) records.get(i);

					Document doc = new Document();
					doc.setId((Long) cols[0]);
					doc.setDocRef((Long) cols[2]);
					doc.setFileName((String) cols[14]);
					doc.setType((String) cols[17]);
					doc.setDocRefType((String) cols[27]);

					if (folderId != null) {
						Folder f = new Folder();
						f.setId(folderId);
						doc.setFolder(f);
					}

					// Replace with the real document if this is an alias
					if (doc.getDocRef() != null && doc.getDocRef().longValue() != 0L) {
						long aliasId = doc.getId();
						long aliasDocRef = doc.getDocRef();
						String aliasDocRefType = doc.getDocRefType();
						String aliasFileName = doc.getFileName();
						String aliasType = doc.getType();
						doc = dao.findById(aliasDocRef);
						
						if (doc != null) {
							dao.initialize(doc);
							doc.setId(aliasId);
							doc.setDocRef(aliasDocRef);
							doc.setDocRefType(aliasDocRefType);
							doc.setFileName(aliasFileName);
							doc.setType(aliasType);
						} else
							continue;
					} else {
						doc.setStartPublishing((Date) cols[22]);
						doc.setStopPublishing((Date) cols[23]);
						doc.setPublished((Integer) cols[24]);

						if (doc.isPublishing() || user.isMemberOf("admin") || user.isMemberOf("publisher")) {
							doc.setCustomId((String) cols[1]);
							doc.setVersion((String) cols[4]);
							doc.setLastModified((Date) cols[5]);
							doc.setDate((Date) cols[6]);
							doc.setPublisher((String) cols[7]);
							doc.setCreation((Date) cols[8]);
							doc.setCreator((String) cols[9]);
							doc.setFileSize((Long) cols[10]);
							doc.setImmutable((Integer) cols[11]);
							doc.setIndexed((Integer) cols[12]);
							doc.setLockUserId((Long) cols[13]);
							doc.setStatus((Integer) cols[15]);
							doc.setSigned((Integer) cols[16]);
							doc.setRating((Integer) cols[18]);
							doc.setFileVersion((String) cols[19]);
							doc.setComment((String) cols[20]);
							doc.setWorkflowStatus((String) cols[21]);
							doc.setExtResId((String) cols[25]);
							doc.setTemplateName((String) cols[26]);
							doc.setStamped((Integer) cols[28]);
							doc.setLockUser((String) cols[29]);
							doc.setPassword((String) cols[30]);
							doc.setPages((Integer) cols[31]);

							if (!extValues.isEmpty())
								for (String name : attrs) {
									String val = extValues.get(doc.getId() + "-" + name);
									if (val != null) {
										doc.setValue(name, val);
									}
								}
						}
					}

					if (doc.isPublishing() || user.isMemberOf("admin") || user.isMemberOf("publisher"))
						documents.add(doc);
				}

				// If a sorting is specified sort the collection of documents
				if (StringUtils.isNotEmpty(sort))
					Collections.sort(documents, DocumentComparator.getComparator(sort));

				int begin = (page - 1) * max;
				int end = Math.min(begin + max - 1, documents.size() - 1);
				for (int i = begin; i <= end; i++)
					documentRecords.add(documents.get(i));
			}

			/*
			 * Iterate over the documents printing the output
			 */
			for (Document doc : documentRecords) {
				writer.print("<document>");
				writer.print("<id>" + doc.getId() + "</id>");
				if (doc.getFolder() != null)
					writer.print("<folderId>" + doc.getFolder().getId() + "</folderId>");
				writer.print("<customId><![CDATA[" + (doc.getCustomId() != null ? doc.getCustomId() : "")
						+ "]]></customId>");
				if (doc.getDocRef() != null) {
					writer.print("<docref>" + doc.getDocRef() + "</docref>");
					if (doc.getDocRefType() != null)
						writer.print("<docrefType>" + doc.getDocRefType() + "</docrefType>");
				}

				writer.print("<icon>" + FilenameUtils.getBaseName(doc.getIcon()) + "</icon>");
				writer.print("<version>" + doc.getVersion() + "</version>");
				writer.print("<lastModified>" + (doc.getLastModified() != null ? df.format(doc.getLastModified()) : "")
						+ "</lastModified>");
				writer.print("<published>" + (doc.getDate() != null ? df.format(doc.getDate()) : "") + "</published>");
				writer.print("<publisher><![CDATA[" + doc.getPublisher() + "]]></publisher>");
				writer.print("<created>" + (doc.getCreation() != null ? df.format(doc.getCreation()) : "")
						+ "</created>");
				writer.print("<creator><![CDATA[" + doc.getCreator() + "]]></creator>");
				writer.print("<size>" + doc.getFileSize() + "</size>");

				writer.print("<status>" + doc.getStatus() + "</status>");
				writer.print("<immutable>" + doc.getImmutable() + "</immutable>");
				writer.print("<indexed>" + doc.getIndexed() + "</indexed>");
				writer.print("<password>" + StringUtils.isNotEmpty(doc.getPassword()) + "</password>");
				writer.print("<signed>" + doc.getSigned() + "</signed>");
				writer.print("<stamped>" + doc.getStamped() + "</stamped>");
				writer.print("<bookmarked>" + (bookmarks.contains(doc.getId()) || bookmarks.contains(doc.getDocRef()))
						+ "</bookmarked>");

				if (doc.getLockUserId() != null)
					writer.print("<lockUserId>" + doc.getLockUserId() + "</lockUserId>");
				if (doc.getLockUser() != null)
					writer.print("<lockUser><![CDATA[" + doc.getLockUser() + "]]></lockUser>");
				writer.print("<filename><![CDATA[" + doc.getFileName() + "]]></filename>");
				writer.print("<type><![CDATA[" + doc.getType() + "]]></type>");

				writer.print("<rating>rating" + (doc.getRating() != null ? doc.getRating() : "0") + "</rating>");
				writer.print("<fileVersion><![CDATA[" + doc.getFileVersion() + "]]></fileVersion>");
				writer.print("<comment><![CDATA[" + (doc.getComment() != null ? doc.getComment() : "")
						+ "]]></comment>");
				writer.print("<workflowStatus><![CDATA["
						+ (doc.getWorkflowStatus() != null ? doc.getWorkflowStatus() : "") + "]]></workflowStatus>");
				writer.print("<startPublishing>" + df.format(doc.getStartPublishing()) + "</startPublishing>");
				if (doc.getStopPublishing() != null)
					writer.print("<stopPublishing>" + df.format(doc.getStopPublishing()) + "</stopPublishing>");
				else
					writer.print("<stopPublishing></stopPublishing>");
				writer.print("<publishedStatus>" + (doc.isPublishing() ? "yes" : "no") + "</publishedStatus>");

				if (doc.getExtResId() != null)
					writer.print("<extResId><![CDATA[" + doc.getExtResId() + "]]></extResId>");

				if (doc.getTemplateName() != null)
					writer.print("<template><![CDATA[" + doc.getTemplateName() + "]]></template>");

				if (!extValues.isEmpty())
					for (String name : attrs) {
						Object val = doc.getValue(name);
						if (val != null)
							writer.print("<ext_" + name + "><![CDATA[" + val + "]]></ext_" + name + ">");
					}
				writer.print("</document>");
			}

			writer.write("</list>");
		} catch (Throwable e) {
			log.error(e.getMessage(), e);
			if (e instanceof ServletException)
				throw (ServletException) e;
			else if (e instanceof IOException)
				throw (IOException) e;
			else
				throw new ServletException(e.getMessage(), e);
		}
	}
}