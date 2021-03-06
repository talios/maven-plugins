package com.goldin.plugins.copy

import static com.goldin.plugins.common.GMojoUtils.*
import com.goldin.plugins.common.ThreadLocals
import java.util.jar.Attributes
import java.util.jar.Manifest
import org.apache.maven.artifact.Artifact
import org.apache.maven.artifact.resolver.MultipleArtifactsNotFoundException
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.logging.Log
import org.apache.maven.project.MavenProject
import org.gcontracts.annotations.Ensures
import org.gcontracts.annotations.Requires
import org.apache.maven.shared.artifact.filter.collection.*

/**
 * {@link CopyMojo} helper class.
 *
 * Class is marked "final" as it's not meant for subclassing.
 * Methods are marked as "protected" to allow package-access only.
 */
@SuppressWarnings( 'FinalClassWithProtectedMember' )
final class CopyMojoHelper
{

   /**
    * Convert path to its canonical form.
    *
    * @param s path to convert
    * @return path in canonical form
    */
    protected String canonicalPath ( String s )
    {
        ( s && ( ! net().isNet( s ))) ? new File( s ).canonicalPath.replace( '\\', '/' ) : s
    }


    /**
     * Analyzes patterns specified and updates them if required:
     * - if any of them is comma or space-separated, splits it to additional patterns
     * - if any of them starts with "file:" or "classpath:", each line in the resource
     *   loaded is converted to a pattern
     *
     * @param  directory resource directory
     * @param patterns   patterns to analyze
     * @param files      encoding
     *
     * @return updated patterns list
     */
    @Requires({ encoding })
    @Ensures({ result != null })
    protected List<String> updatePatterns( String directory, List<String> patterns, String encoding )
    {
        if ( ! patterns ) { return patterns }

        patterns*.trim().grep().collect {
            String pattern ->
            pattern.startsWith( 'file:'      ) ? new File( pattern.substring( 'file:'.length())).getText( encoding ).readLines()      :
            pattern.startsWith( 'classpath:' ) ? CopyMojo.getResourceAsStream( pattern.substring( 'classpath:'.length())).readLines() :
                                                 split( pattern )
        }.
        flatten().
        collect { String pattern -> ( directory && new File( directory, pattern ).directory ? "$pattern/**" : pattern )}
    }


    /**
     * Scans all dependencies that this project has (including transitive ones) and filters them with scoping
     * and transitivity filters provided in dependency specified.
     *
     * @param dependency     filtering dependency
     * @param failIfNotFound whether execution should fail if zero dependencies are resolved
     * @return               project's dependencies that passed all filters
     */
    @Requires({ dependency })
    @Ensures({ result != null })
    protected List<CopyDependency> getDependencies ( CopyDependency dependency, boolean failIfNotFound )
    {
        assert dependency
        def singleDependency = dependency.groupId && dependency.artifactId

        if ( singleDependency && dependency.getExcludeTransitive( singleDependency ))
        {
            // Simplest case: single <dependency> + <excludeTransitive> is undefined or "true" - dependency is returned
            return [ dependency ]
        }

        /**
         * Iterating over all dependencies and selecting those passing the filters.
         * "test" = "compile" + "provided" + "runtime" + "test" (http://maven.apache.org/pom.html#Dependencies)
         */
        List<ArtifactsFilter> filters   = getFilters( dependency, singleDependency )
        Collection<Artifact>  artifacts = singleDependency ?
            [ buildArtifact( dependency.groupId, dependency.artifactId, dependency.version, dependency.type ) ] :
            ThreadLocals.get( MavenProject ).artifacts

        try
        {
            List<CopyDependency>  dependencies = getArtifacts( artifacts, 'test', 'system' ).
                                                 findAll { Artifact artifact -> filters.every{ it.isArtifactIncluded( artifact ) }}.
                                                 collect { Artifact artifact -> new CopyDependency( artifact ) }

            Log log = ThreadLocals.get( Log )

            log.info( "Resolving dependencies [$dependency]: [${ dependencies.size() }] artifacts found" )
            if ( log.isDebugEnabled()) { log.debug( "Artifacts found: $dependencies" ) }

            assert ( dependencies || ( ! failIfNotFound )), "No dependencies resolved using [$dependency]"
            return dependencies
        }
        catch( e )
        {
            String errorMessage =
                'Failed to resolve and filter dependencies' +
                ( singleDependency ? " using ${ dependency.optional ? 'optional ' : '' }<dependency> [$dependency]" : '' )

            if ( dependency.optional || ( ! failIfNotFound ))
            {
                String exceptionMessage =  ( e instanceof MultipleArtifactsNotFoundException ) ?
                    "${ e.missingArtifacts.size() } missing dependenc${ e.missingArtifacts.size() == 1 ? 'y' : 'ies' } - " +
                    "${ e.missingArtifacts }" : e.toString()
                log.warn( "$errorMessage: $exceptionMessage" )
                return []
            }

            throw new MojoExecutionException( errorMessage, e )
        }
    }


    private List<ArtifactsFilter> getFilters( CopyDependency dependency, boolean singleDependency )
    {
        /**
         * If we got here it's either because dependency is not single (filtered) or because *it is* single
         * with transitivity explicitly enabled (excludeTransitive is set to "false").
         */
        assert dependency
        // noinspection GroovyPointlessBoolean
        assert ( ! singleDependency ) || ( dependency.excludeTransitive == false )

        def c                         = { String s -> split( s ).join( ',' )} // Splits by "," and joins back to loose spaces
        List<ArtifactsFilter> filters = []
        def directDependencies        = singleDependency ?
            [ dependency ] as Set :                               // For single dependency it is it's own direct dependency
            ThreadLocals.get( MavenProject ).dependencyArtifacts  // Direct project dependencies for filtered dependencies

        filters << new ProjectTransitivityFilter( directDependencies, dependency.getExcludeTransitive( singleDependency ))

        if ( dependency.includeScope || dependency.excludeScope )
        {
            filters << new ScopeFilter( c ( dependency.includeScope ), c ( dependency.excludeScope ))
        }

        if ( dependency.includeGroupIds || dependency.excludeGroupIds )
        {
            filters << new GroupIdFilter( c ( dependency.includeGroupIds ), c ( dependency.excludeGroupIds ))
        }

        if ( dependency.includeArtifactIds || dependency.excludeArtifactIds )
        {
            filters << new ArtifactIdFilter( c ( dependency.includeArtifactIds ), c ( dependency.excludeArtifactIds ))
        }

        if ( dependency.includeClassifiers || dependency.excludeClassifiers )
        {
            filters << new ClassifierFilter( c ( dependency.includeClassifiers ), c ( dependency.excludeClassifiers ))
        }

        if ( dependency.includeTypes || dependency.excludeTypes )
        {
            filters << new TypeFilter( c ( dependency.includeTypes ), c ( dependency.excludeTypes ))
        }

        assert ( singleDependency || ( filters.size() > 1 )) : \
               'No filters found in <dependency>. Specify filters like <includeScope> or <includeGroupIds>.'

        filters
    }


    /**
     * Creates Manifest file in temp directory using the data specified.
     *
     * @param manifest data to store in the manifest file
     * @return temporary directory where manifest file is created according to location specified by {@code manifest} argument.
     */
    @Requires({ manifest && manifest.location && manifest.entries })
    @Ensures({ result.directory && result.listFiles() })
    File prepareManifest( CopyManifest manifest )
    {
        final m       = new Manifest()
        final tempDir = file().tempDirectory()
        final f       = new File( tempDir, manifest.location )

        m.mainAttributes[ Attributes.Name.MANIFEST_VERSION ] = '1.0'
        manifest.entries.each{ String key, String value -> m.mainAttributes.putValue( key, value ) }

        file().mkdirs( f.parentFile )
        f.withOutputStream { m.write( it )}

        tempDir
    }
}
