package org.apache.giraph.debugger.instrumenter;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.attribute.FileAttribute;
import java.util.Collection;

import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;
import javassist.NotFoundException;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Descriptor;

import org.apache.giraph.graph.Computation;
import org.apache.giraph.master.MasterCompute;
import org.apache.log4j.Logger;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * The main class that instruments user's ordinary Giraph Computation class with
 * necessary changes for debugging.
 * 
 * @author netj
 */
public class InstrumentGiraphClasses {

  private static Logger LOG = Logger.getLogger(InstrumentGiraphClasses.class);

  private static final String ORIGINAL_CLASS_NAME_SUFFIX = System.getProperty(
    "giraph.debugger.classNameSuffix", "Original");
  private static final String tmpDirNamePrefix = InstrumentGiraphClasses.class
    .getSimpleName();

  public static void main(String[] args) throws IOException, Exception {
    if (args.length < 1) {
      System.err.println("Usage: java ... " + tmpDirNamePrefix
        + " GIRAPH_COMPUTATION_CLASS_NAME  [OUTPUT_DIR]");
      System.exit(1);
    }

    Collection<String> userComputationClassNames = Sets.newHashSet(args[0]);
    String outputDir = (args.length > 1 ? args[1] : null);
    String masterComputeClassName = (args.length > 2 ? args[2] : null);
    // Additional Computation classes
    boolean shouldAnalyzeMaster = (masterComputeClassName != null);
    for (int i = 3; i < args.length; i++) {
      userComputationClassNames.add(args[i]);
      // Don't analyze the MasterCompute class when a chosen list of
      // Computation classes were given
      shouldAnalyzeMaster = false;
    }

    try {
      Collection<CtClass> classesModified = Sets.newHashSet();
      ClassPool classPool = ClassPool.getDefault();
      if (masterComputeClassName != null) {
        if (shouldAnalyzeMaster) {
          // Collect all Computation class names referenced in
          // masterComputeClassName
          LOG.info("Analyzing MasterCompute class: " + masterComputeClassName);
          userComputationClassNames.addAll( //
            collectComputationClassNames( //
              masterComputeClassName, classPool));
        }
        LOG.info("Instrumenting MasterCompute class: " + masterComputeClassName);
        classesModified.addAll( //
          instrumentSandwich(masterComputeClassName,
            AbstractInterceptingMasterCompute.class.getName(), UserMasterCompute.class.getName(),
            BottomInterceptingMasterCompute.class.getName(), classPool));
      }
      for (String userComputationClassName : userComputationClassNames) {
        LOG.info("Instrumenting Computation class: " + userComputationClassName);
        classesModified.addAll( //
          instrumentSandwich(userComputationClassName,
            AbstractInterceptingComputation.class.getName(),
            UserComputation.class.getCanonicalName(),
            BottomInterceptingComputation.class.getName(), classPool));
      }

      // Finally, write the modified classes so that a new jar can be
      // created or an existing one can be updated.
      String jarRoot = outputDir != null ? outputDir : Files.createTempDirectory(tmpDirNamePrefix,
        new FileAttribute[0]).toString();
      LOG.info("Writing " + classesModified.size() + " instrumented classes to " + jarRoot);
      for (CtClass c : classesModified) {
        LOG.debug(" writing class " + c.getName());
        c.writeFile(jarRoot);
      }

      LOG.info("Finished instrumentation");

      if (outputDir == null)
        // Show where we produced the instrumented .class files (unless
        // specified)
        System.out.println(jarRoot);
      System.exit(0);
    } catch (NotFoundException e) {
      System.err.println("Some Giraph Computation or MasterCompute classes were not found");
      System.exit(1);
    } catch (CannotCompileException e) {
      e.printStackTrace();
      System.err.println("Cannot instrument the given Giraph Computation or MasterCompute classes");
      System.exit(2);
    } catch (IOException e) {
      e.printStackTrace();
      System.err
        .println("Cannot write the instrumented Giraph Computation and/or MasterCompute classes");
      System.exit(4);
    }

  }

  protected static Collection<String> collectComputationClassNames(String masterComputeClassName,
    ClassPool classPool) throws NotFoundException {
    Collection<String> classNames = Lists.newArrayList();
    CtClass computationClass = classPool.get(Computation.class.getName());
    CtClass rootMasterComputeClass = classPool.get(MasterCompute.class.getName());
    CtClass masterComputeClass = classPool.get(masterComputeClassName);
    CtClass mc = masterComputeClass;
    while (mc != null && !mc.equals(rootMasterComputeClass)) {
      // find all class names appearing in the master compute class
      @SuppressWarnings("unchecked")
      Collection<String> classNamesInMasterCompute = Lists.newArrayList(mc.getRefClasses());
      // as well as in string literals
      ConstPool constPool = mc.getClassFile().getConstPool();
      for (int i = 1; i < constPool.getSize(); i++) {
        switch (constPool.getTag(i)) {
        case ConstPool.CONST_String:
          classNamesInMasterCompute.add(constPool.getStringInfo(i));
          break;
        }
      }
      // collect Computation class names
      for (String className : classNamesInMasterCompute) {
        try {
          if (classPool.get(className).subtypeOf(computationClass))
            classNames.add(className);
        } catch (Exception e) {
        }
      }

      // repeat above for all superclasses of given masterComputeClass
      mc = mc.getSuperclass();
    }
    return classNames;
  }

  protected static Collection<CtClass> instrumentSandwich(String targetClassName,
    String topClassName, String mockTargetClassName, String bottomClassName, ClassPool classPool)
    throws NotFoundException, CannotCompileException, ClassNotFoundException {
    Collection<CtClass> classesModified = Sets.newHashSet();
    // Load the involved classes with Javassist
    LOG.debug("Looking for classes to instrument: " + targetClassName + "...");
    String alternativeClassName = targetClassName + ORIGINAL_CLASS_NAME_SUFFIX;
    CtClass targetClass = classPool.getAndRename(targetClassName, alternativeClassName);
    // We need two classes: one at the bottom (subclass) and
    // another at the top (superclass).
    CtClass topClass = classPool.get(topClassName);
    CtClass bottomClass = classPool.getAndRename(bottomClassName, targetClassName);

    LOG.debug("  target class to instrument (targetClass):\n" + getGenericsName(targetClass));
    LOG.debug("  class to instrument at top (topClass):\n" + getGenericsName(topClass));
    LOG.debug("  class to instrument at bottom (bottomClass):\n" + getGenericsName(bottomClass));

    // 1. To intercept other Giraph API calls by user's computation
    // class:
    // We need to inject a superclass at the highest level of the
    // inheritance hierarchy, i.e., make the top class become a
    // superclass of the class that directly extends
    // AbstractComputation.
    // 1-a. Find the user's base class that extends the top class'
    // superclass.
    LOG.debug("Looking for user's top class that extends "
      + getGenericsName(topClass.getSuperclass()));
    CtClass targetTopClass = targetClass;
    while (!targetTopClass.equals(Object.class)
      && !targetTopClass.getSuperclass().equals(topClass.getSuperclass())) {
      targetTopClass = targetTopClass.getSuperclass();
    }
    if (targetTopClass.equals(Object.class)) {
      throw new NotFoundException(targetClass.getName() + " must extend "
        + topClass.getSuperclass().getName());
    }
    LOG.debug("  class to inject topClass on top of (targetTopClass):\n"
      + getGenericsName(targetTopClass));
    // 1-b. Mark user's class as abstract and erase any final modifier.
    LOG.debug("Marking targetClass as abstract and non-final...");
    {
      int mod = targetClass.getModifiers();
      mod |= Modifier.ABSTRACT;
      mod &= ~Modifier.FINAL;
      targetClass.setModifiers(mod);
      classesModified.add(targetClass);
    }
    if (!targetTopClass.equals(topClass)) {
      // 1-c. Inject the top class by setting it as the superclass of
      // user's class that extends its superclass (AbstractComputation).
      LOG.debug("Injecting topClass on top of targetTopClass...");
      targetTopClass.setSuperclass(topClass);
      targetTopClass.replaceClassName(topClass.getSuperclass().getName(), topClass.getName());
      classesModified.add(targetTopClass);
      {
        // XXX Unless we take care of generic signature as well,
        // GiraphConfigurationValidator will complain.
        String jvmNameForTopClassSuperclass = Descriptor.of(topClass.getSuperclass()).replaceAll(
          ";$", "");
        String jvmNameForTopClass = Descriptor.of(topClass).replaceAll(";$", "");
        String genSig = targetTopClass.getGenericSignature();
        if (genSig != null) {
          String genSig2 = genSig.replace(jvmNameForTopClassSuperclass, jvmNameForTopClass);
          targetTopClass.setGenericSignature(genSig2);
        }
      }
    }
    // 1-d. Then, make the bottomClass extend user's computation, taking
    // care of generics signature as well.
    LOG.debug("Attaching bottomClass beneath targetClass...");
    bottomClass.replaceClassName(mockTargetClassName, targetClass.getName());
    bottomClass.setSuperclass(targetClass);
    bottomClass.setGenericSignature(Descriptor.of(targetClass));
    classesModified.add(bottomClass);

    // 2. To intercept compute() and other calls that originate from
    // Giraph:
    // We need to extend user's computation class pass that as the
    // computation class to Giraph. The new subclass will override
    // compute(), so we may have to remove the "final" marker on user's
    // compute() method.
    // 2-a. Find all methods that we override in bottomClass.
    LOG.debug("For each method to intercept," + " changing Generics signature of bottomClass, and"
      + " erasing final modifier...");
    for (CtMethod overridingMethod : bottomClass.getMethods()) {
      if (!overridingMethod.hasAnnotation(Intercept.class))
        continue;
      Intercept annotation = (Intercept) overridingMethod.getAnnotation(Intercept.class);
      String targetMethodName = annotation.renameTo();
      if (targetMethodName == null || targetMethodName.isEmpty())
        targetMethodName = overridingMethod.getName();
      // 2-b. Copy generics signature to the overriding method if
      // necessary.
      CtMethod targetMethod = targetClass.getMethod(targetMethodName,
        overridingMethod.getSignature());
      LOG.debug(" from: " + overridingMethod.getName() + overridingMethod.getGenericSignature());
      LOG.debug("   to: " + targetMethod.getName() + targetMethod.getGenericSignature());
      if (overridingMethod.getGenericSignature() != null) {
        overridingMethod.setGenericSignature(targetMethod.getGenericSignature());
        classesModified.add(overridingMethod.getDeclaringClass());
      }
      // 2-c. Remove final marks from them.
      int mod = targetMethod.getModifiers();
      if ((mod & Modifier.FINAL) == 0)
        continue;
      mod &= ~Modifier.FINAL;
      targetMethod.setModifiers(mod);
      LOG.debug(" erasing final modifier from " + targetMethod.getName() + "() of "
        + targetMethod.getDeclaringClass());
      // 2-d. Rename the overriding method if necessary.
      if (!overridingMethod.getName().equals(targetMethodName)) {
        overridingMethod.setName(targetMethodName);
        classesModified.add(overridingMethod.getDeclaringClass());
      }
      // 2-e. Remember them for later.
      classesModified.add(targetMethod.getDeclaringClass());
    }
    LOG.debug("Finished instrumenting " + targetClassName);
    LOG.debug("            topClass=\n" + getGenericsName(topClass) + "\n" + topClass);
    LOG.debug("      targetTopClass=\n" + getGenericsName(targetTopClass) + "\n" + targetTopClass);
    LOG.debug("         targetClass=\n" + getGenericsName(targetClass) + "\n" + targetClass);
    LOG.debug("         bottomClass=\n" + getGenericsName(bottomClass) + "\n" + bottomClass);
    return classesModified;
  }

  protected static String getGenericsName(CtClass clazz) {
    return clazz.getName() + " (" + clazz.getGenericSignature() + ")";
  }
}
