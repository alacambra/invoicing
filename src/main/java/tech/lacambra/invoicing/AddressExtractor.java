package tech.lacambra.invoicing;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.stream.JsonCollectors;
import javax.mail.*;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import java.io.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;


public class AddressExtractor {

  private static final Logger LOGGER = Logger.getLogger(AddressExtractor.class.getName());

  public static void main(String[] args) throws IOException {
    new AddressExtractor().run();
  }

  private JsonObject loadConfig() {
    String location = System.getenv("EMAIL_PROPS_FILE") != null ?
        System.getenv("EMAIL_PROPS_FILE") :
        System.getProperty("email.props.file.properties");

    if (location == null) {
      throw new RuntimeException("properties not found");
    }

    LOGGER.info("[loadConfig] Using location " + location);


    try {
      return Json.createReader(new FileInputStream(location)).readObject();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private Properties loadProperties(JsonObject config) {
    Properties properties = new Properties();
    properties.put("host", config.getString("host"));
    properties.put("username", config.getString("username"));
    properties.put("password", config.getString("password"));
    properties.put("protocol", config.getString("protocol"));

    return properties;
  }


  Store loadEmailStore() {
    Properties props = loadProperties(loadConfig());

    String host = props.getProperty("host");
    String username = props.getProperty("username");
    String password = props.getProperty("password");
    String protocol = props.getProperty("protocol");

    try {
      //Connect to the server
      Session session = Session.getDefaultInstance(props, null);
      Store store = session.getStore(protocol);
      store.connect(host, username, password);

      return store;
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }

  }

  void run() throws IOException {
    try {
      Store store = loadEmailStore();
      //open the inbox folder
      Folder inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      List<Message> messages = Arrays.asList(
          inbox.search(betweenDatesTerm(
              LocalDateTime.now().minusDays(200).with(LocalTime.MIN),
              LocalDateTime.now().minusDays(0).with(LocalTime.MAX)
              )
          ));

      messages.stream().filter(this::hasRelevantSubject).peek(this::getAttachmemnt).map(this::msgToJsonObject).forEach(System.out::println);
      inbox.close(false);
      store.close();

    } catch (NoSuchProviderException nspe) {
      System.err.println("invalid provider name");
    } catch (MessagingException me) {
      System.err.println("messaging exception");
      me.printStackTrace();
    }
  }

  private SearchTerm betweenDatesTerm(LocalDateTime start, LocalDateTime end) {

    SearchTerm newerThan = new ReceivedDateTerm(ComparisonTerm.GE, Date.from(start.toInstant(ZoneOffset.of("+1"))));
    SearchTerm olderThan = new ReceivedDateTerm(ComparisonTerm.LE, Date.from(end.toInstant(ZoneOffset.of("+1"))));
    return new AndTerm(olderThan, newerThan);


  }

  public void getAttachmemnt(Message message) {
    List<File> attachments = new ArrayList<File>();
    Multipart multipart = null;
    try {
      multipart = (Multipart) message.getContent();
      for (int i = 0; i < multipart.getCount(); i++) {
        BodyPart bodyPart = multipart.getBodyPart(i);
        if (!Part.ATTACHMENT.equalsIgnoreCase(bodyPart.getDisposition()) &&
            (bodyPart.getFileName() == null || bodyPart.getFileName().isEmpty())) {
          continue; // dealing with attachments only
        }
        InputStream is = bodyPart.getInputStream();
        File f = new File("tmp_" + bodyPart.getFileName());
        FileOutputStream fos = new FileOutputStream(f);
        byte[] buf = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(buf)) != -1) {
          fos.write(buf, 0, bytesRead);
        }
        fos.close();
        attachments.add(f);
      }
    } catch (IOException | MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private JsonObject msgToJsonObject(Message message) {

    try {
      return Json.createObjectBuilder()
          .add("from", recipientsToString(message))
          .add("to", Stream.of(message.getFrom()).map(this::addressToStr).map(Object::toString).collect(Collectors.joining(", ")))
          .add("subject", message.getSubject())
          .add("messageNumber", message.getMessageNumber())
          .add("received", message.getSentDate().toString())
          .build();

    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean isInvoiceEmail(Message message) {

    String filterEmail = loadConfig().getString("filterEmail");
    LOGGER.info("[isInvoiceEmail] Filtering email " + filterEmail);


    try {
      return List.of(message.getAllRecipients()).toString().contains(filterEmail);
    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }
  }

  private boolean hasRelevantSubject(Message message) {

    JsonArray relevantSubjects = loadConfig().getJsonArray("relevantSubjects");
    LOGGER.info("[hasRelevantSubject] Relevant subjects: " + relevantSubjects);

    return relevantSubjects.stream().anyMatch(subject -> {
      try {
        return message.getSubject().toLowerCase().contains(((JsonString) subject).getString().toLowerCase())
            ||
            ((JsonString) subject).getString().toLowerCase().contains(message.getSubject().toLowerCase());
      } catch (MessagingException e) {
        throw new RuntimeException(e);
      }
    });
  }

  private JsonArray recipientsToString(Message message) {


    try {
      return Stream.of(message.getAllRecipients())
          .map(this::addressToStr)
//          .filter(jsonObject -> jsonObject.getString("txt").contains("invoice@lacambra.tech"))
          .collect(JsonCollectors.toJsonArray());

    } catch (MessagingException e) {
      throw new RuntimeException(e);
    }


  }


  JsonObject addressToStr(Address address) {
    return Json.createObjectBuilder().add("type", address.getType()).add("txt", address.toString()).build();
  }


  private String getFrom(Message msg) throws MessagingException {
    String from = "";
    Address a[] = msg.getAllRecipients();
    if (a == null) return null;
    for (int i = 0; i < a.length; i++) {
      Address address = a[i];
      from = from + address.toString();
    }

    return from;
  }

  private String removeQuotes(String stringToModify) {
    int indexOfFind = stringToModify.indexOf(stringToModify);
    if (indexOfFind < 0) return stringToModify;

    StringBuffer oldStringBuffer = new StringBuffer(stringToModify);
    StringBuffer newStringBuffer = new StringBuffer();
    for (int i = 0, length = oldStringBuffer.length(); i < length; i++) {
      char c = oldStringBuffer.charAt(i);
      if (c == '"' || c == '\'') {
        // do nothing
      } else {
        newStringBuffer.append(c);
      }

    }
    return new String(newStringBuffer);
  }

}
