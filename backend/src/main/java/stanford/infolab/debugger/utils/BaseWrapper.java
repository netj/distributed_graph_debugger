package stanford.infolab.debugger.utils;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.giraph.utils.ReflectionUtils;
import org.apache.giraph.utils.WritableUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessage;

/** 
 * Base class for all wrapper classes that wrap a protobuf.
 * 
 * @author semihsalihoglu
 */
public abstract class BaseWrapper {

  <T extends Writable> T makeCloneOf(T actualWritable, Class<T> clazz) {
    T idCopy = newInstance(clazz);
    // Return value is null if clazz is assignable to NullWritable.
    if (idCopy == null) {
      return actualWritable;
    }
    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
    DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);
    try {
      actualWritable.write(dataOutputStream);
    } catch (IOException e) {
      // Throwing a runtime exception because the methods that call other methods
      // such as addNeighborWrapper or addOutgoingMessageWrapper, implement abstract classes
      // or interfaces of Giraph that we can't edit to include a throws statement.
      throw new RuntimeException(e);
    }
    // 
    if (byteArrayOutputStream.toByteArray() != null) {
      WritableUtils.readFieldsFromByteArray(byteArrayOutputStream.toByteArray(), idCopy);
      byteArrayOutputStream.reset();
    }
    return idCopy;
  }

  @SuppressWarnings("unchecked")
  protected <U> Class<U> castClassToUpperBound(Class<?> clazz, Class<U> upperBound) {
    if (!upperBound.isAssignableFrom(clazz)) {
      throw new IllegalArgumentException("The class " + clazz.getName() + " is not a subclass of "
          + upperBound.getName());
    }
    return (Class<U>) clazz;
  }

  void fromByteString(ByteString byteString, Writable writable) {
    if (writable != null) {
      WritableUtils.readFieldsFromByteArray(byteString.toByteArray(), writable);
    }
  }
  
  ByteString toByteString(Writable writable) {
    return ByteString.copyFrom(WritableUtils.writeToByteArray(writable));
  }
  
  static <T> T newInstance(Class<T> theClass) {
    return NullWritable.class.isAssignableFrom(theClass) 
        ? null : ReflectionUtils.newInstance(theClass);
  }

  
  public void save(String fileName) throws IOException {
    try (FileOutputStream output = new FileOutputStream(fileName)) {
      buildProtoObject().writeTo(output);
      output.close();
    }
  }

  public void saveToHDFS(FileSystem fs, String fileName) throws IOException {
    System.out.println("Saving " + this.getClass().getCanonicalName() + " to file: " + fileName);
    Path pt = new Path(fileName);
    buildProtoObject().writeTo(fs.create(pt, true).getWrappedStream());
  }
  
  public abstract GeneratedMessage buildProtoObject();

  public void load(String fileName) throws ClassNotFoundException, IOException, InstantiationException, IllegalAccessException {
    loadFromProto(parseProtoFromInputStream(new FileInputStream(fileName)));
  }

  public void loadFromHDFS(FileSystem fs, String fileName) throws ClassNotFoundException,
    IOException, InstantiationException, IllegalAccessException {
    loadFromProto(parseProtoFromInputStream(fs.open(new Path(fileName))));
  }
  
  public abstract GeneratedMessage parseProtoFromInputStream(InputStream inputStream)
    throws IOException ;

  public abstract void loadFromProto(GeneratedMessage protoObject) throws ClassNotFoundException,
    IOException, InstantiationException, IllegalAccessException;
}
