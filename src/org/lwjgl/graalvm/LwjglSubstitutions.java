package org.lwjgl.graalvm;

import java.util.Optional;

import org.lwjgl.Version;
import org.lwjgl.Version.BuildType;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.RecomputeFieldValue;
import com.oracle.svm.core.annotate.RecomputeFieldValue.Kind;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(org.lwjgl.Version.class)
final class Target_org_lwjgl_Version {
    
    @Alias
    @RecomputeFieldValue(kind = Kind.FromAlias, isFinal = true)
    public static int VERSION_MAJOR, VERSION_MINOR, VERSION_REVISION;

    @Alias
    @RecomputeFieldValue(kind = Kind.FromAlias, isFinal = true)
    public static BuildType BUILD_TYPE;

    @Substitute
    public static String getVersion() {
        String implVersion = Version.class.getPackage().getImplementationVersion();
        return String.valueOf(VERSION_MAJOR) +
                '.' + VERSION_MINOR +
                '.' + VERSION_REVISION + BUILD_TYPE.postfix +
                ' ' + Optional.ofNullable(implVersion).orElse("SNAPSHOT");
    }
}

/** Dummy class with the file's name. */
public class LwjglSubstitutions {
}
