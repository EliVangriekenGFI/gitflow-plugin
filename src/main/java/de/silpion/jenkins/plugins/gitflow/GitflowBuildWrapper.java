package de.silpion.jenkins.plugins.gitflow;

import de.silpion.jenkins.plugins.gitflow.action.AbstractGitflowAction;
import de.silpion.jenkins.plugins.gitflow.action.GitflowActionFactory;
import de.silpion.jenkins.plugins.gitflow.cause.AbstractGitflowCause;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Item;
import hudson.plugins.git.GitSCM;
import hudson.security.Permission;
import hudson.security.PermissionScope;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import jenkins.model.Jenkins;
import jenkins.util.NonLocalizable;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

/**
 * Wraps a build that works on a Git repository. It enables the creation of Git releases, respecting the
 * <a href="http://nvie.com/posts/a-successful-git-branching-model/">Git Flow</a>.
 *
 * @author Marc Rohlfs, Silpion IT-Solutions GmbH - rohlfs@silpion.de
 */
public class GitflowBuildWrapper extends BuildWrapper {

    @DataBoundConstructor
    public GitflowBuildWrapper() {
        // No job config params so far.
    }

    /** {@inheritDoc} */
    @Override
    public void preCheckout(@SuppressWarnings("rawtypes") final AbstractBuild build, final Launcher launcher, final BuildListener listener) throws IOException, InterruptedException {
        super.preCheckout(build, launcher, listener);

        // Provide a parameter value object, that creates a build wrapper which omits the main build on demand.
        final AbstractGitflowCause gitflowCause = (AbstractGitflowCause) build.getCause(AbstractGitflowCause.class);
        if (gitflowCause != null && gitflowCause.isOmitMainBuild()) {
            build.addAction(new OmitMainBuildParametersAction());
        }
    }

    @Override
    public Environment setUp(@SuppressWarnings("rawtypes") final AbstractBuild build, final Launcher launcher, final BuildListener listener)
            throws IOException, InterruptedException {
        final Environment buildEnvironment;

        final AbstractGitflowAction<?, ?> gitflowAction = GitflowActionFactory.newInstance(build, launcher, listener);

        gitflowAction.beforeMainBuild();

        // Cause the omission of the main build - the build will be interrupted by a subsequent build wrapper then.
        final OmitMainBuildParametersAction omitMainBuildParametersAction = build.getAction(OmitMainBuildParametersAction.class);
        if (omitMainBuildParametersAction != null) {
            omitMainBuildParametersAction.interrupt(listener.getLogger(), gitflowAction.getActionName());
        }

        buildEnvironment = new Environment() {

            /** {@inheritDoc} */
            @Override
            public void buildEnvVars(final Map<String, String> env) {
                env.putAll(gitflowAction.getAdditionalBuildEnvVars());
            }

            /** {@inheritDoc} */
            @Override
            public boolean tearDown(@SuppressWarnings({ "hiding", "rawtypes" }) final AbstractBuild build,
                                    @SuppressWarnings("hiding") final BuildListener listener) throws IOException, InterruptedException {

                gitflowAction.afterMainBuild();

                return true;
            }
        };

        return buildEnvironment;
    }

    public static boolean hasReleasePermission(@SuppressWarnings("rawtypes") AbstractProject job) {
        return job.hasPermission(DescriptorImpl.EXECUTE_GITFLOW);
    }

    @Override
    public Collection<? extends Action> getProjectActions(@SuppressWarnings("rawtypes") final AbstractProject job) {
        return Collections.singletonList(new GitflowProjectAction(job));
    }

    /**
     * Returns the one and only {@link GitflowBuildWrapper.DescriptorImpl} instance.
     *
     * @return the one and only {@link GitflowBuildWrapper.DescriptorImpl} instance.
     * @throws AssertionError if the descriptor is missing.
     */
    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public static DescriptorImpl getGitflowBuildWrapperDescriptor() {
        return (GitflowBuildWrapper.DescriptorImpl) Jenkins.getInstance().getDescriptorOrDie(GitflowBuildWrapper.class);
    }

    /**
     * The descriptor for the <i>Jenkins Gitflow Plugin</i>.
     *
     * @author Marc Rohlfs, Silpion IT-Solutions GmbH - rohlfs@silpion.de
     */
    @Extension
    public static class DescriptorImpl extends BuildWrapperDescriptor {

        /** The configurable usage permission for the <i>Jenkins Gitflow Plugin</i>. */
        public static final Permission EXECUTE_GITFLOW = new Permission(Item.PERMISSIONS, "Gitflow", new NonLocalizable("Gitflow"), Jenkins.ADMINISTER,
                                                                        PermissionScope.ITEM);

        private String masterBranch = "master";
        private String developBranch = "develop";
        private String releaseBranchPrefix = "release/";
        private String hotfixBranchPrefix = "hotfix/";
        private String featureBranchPrefix = "feature/";
        private String versionTagPrefix = "";
        private boolean markSuccessfulBuildUnstableOnBrokenBranches = false;

        public DescriptorImpl() {
            super(GitflowBuildWrapper.class);
            this.load();
        }

        @Override
        public boolean isApplicable(final AbstractProject<?, ?> item) {
            return item.getScm() instanceof GitSCM;
        }

        @Override
        public boolean configure(StaplerRequest staplerRequest, JSONObject json) throws FormException {
            this.masterBranch = json.getString("masterBranch");
            this.developBranch = json.getString("developBranch");
            this.releaseBranchPrefix = json.getString("releaseBranchPrefix");
            this.hotfixBranchPrefix = json.getString("hotfixBranchPrefix");
            this.versionTagPrefix = json.getString("versionTagPrefix");
            this.featureBranchPrefix = json.getString("featureBranchPrefix");
            this.markSuccessfulBuildUnstableOnBrokenBranches = json.getBoolean("markSuccessfulBuildUnstableOnBrokenBranches");

            this.save();
            return true; // everything is alright so far
        }

        /**
         * Returns the <i>Gitflow</i> branch type for the given simple branch name.
         *
         * @param branchName the simple branch name to get the branch type for.
         * @return the <i>Gitflow</i> branch type for the given simple branch name.
         */
        public String getBranchType(final String branchName) {
            if (StringUtils.equals(branchName, this.masterBranch)) {
                return "master";
            } else if (StringUtils.equals(branchName, this.developBranch)) {
                return "develop";
            } else if (StringUtils.startsWith(branchName, this.releaseBranchPrefix)) {
                return "release";
            } else if (StringUtils.startsWith(branchName, this.hotfixBranchPrefix)) {
                return "hotfix";
            } else if (StringUtils.startsWith(branchName, this.featureBranchPrefix)) {
                return "feature";
            } else {
                return "unknown";
            }
        }

        @Override
        public String getDisplayName() {
            return "Gitflow";
        }

        public String getMasterBranch() {
            return this.masterBranch;
        }

        public String getDevelopBranch() {
            return this.developBranch;
        }

        @SuppressWarnings("UnusedDeclaration")
        public String getFeatureBranchPrefix() {
            return this.featureBranchPrefix;
        }

        public String getReleaseBranchPrefix() {
            return this.releaseBranchPrefix;
        }

        public String getHotfixBranchPrefix() {
            return this.hotfixBranchPrefix;
        }

        public String getVersionTagPrefix() {
            return this.versionTagPrefix;
        }

        public boolean isMarkSuccessfulBuildUnstableOnBrokenBranches() {
            return this.markSuccessfulBuildUnstableOnBrokenBranches;
        }
    }
}
