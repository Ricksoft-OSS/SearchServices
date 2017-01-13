package org.alfresco.rest.comments;

import org.alfresco.dataprep.CMISUtil.DocumentType;
import org.alfresco.rest.RestTest;
import org.alfresco.rest.exception.JsonToModelConversionException;
import org.alfresco.rest.model.RestCommentModel;
import org.alfresco.rest.model.RestErrorModel;
import org.alfresco.utility.constants.UserRole;
import org.alfresco.utility.data.DataUser.ListUserWithRoles;
import org.alfresco.utility.model.FileModel;
import org.alfresco.utility.model.FolderModel;
import org.alfresco.utility.model.SiteModel;
import org.alfresco.utility.model.TestGroup;
import org.alfresco.utility.model.UserModel;
import org.alfresco.utility.report.Bug;
import org.alfresco.utility.testrail.ExecutionType;
import org.alfresco.utility.testrail.annotation.TestRail;
import org.springframework.http.HttpStatus;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class UpdateCommentSanityTests extends RestTest
{
    private UserModel adminUserModel;
    private FileModel document;
    private SiteModel siteModel;
    private RestCommentModel commentModel;
    private ListUserWithRoles usersWithRoles;

    @BeforeClass(alwaysRun=true)
    public void dataPreparation() throws Exception
    {
        adminUserModel = dataUser.getAdminUser();
        restClient.authenticateUser(adminUserModel);
        siteModel = dataSite.usingUser(adminUserModel).createPublicRandomSite();
        
        document = dataContent.usingSite(siteModel).usingUser(adminUserModel).createContent(DocumentType.TEXT_PLAIN);
        usersWithRoles = dataUser.addUsersWithRolesToSite(siteModel,UserRole.SiteManager, UserRole.SiteCollaborator, UserRole.SiteConsumer, UserRole.SiteContributor);
    }

    @TestRail(section = { TestGroup.REST_API,
            TestGroup.COMMENTS }, executionType = ExecutionType.SANITY, description = "Verify Admin user updates comments and status code is 200")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void adminIsAbleToUpdateHisComment() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUserModel);
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("This is a new comment added by admin");
        String updatedContent = "This is the updated comment with admin user";
        restClient.withCoreAPI().usingResource(document).updateComment(commentModel, updatedContent)      
                   .assertThat().field("content").isNotEmpty()
                   .and().field("content").is(updatedContent);
        restClient.assertStatusCodeIs(HttpStatus.OK);
    }

    @TestRail(section = { TestGroup.REST_API,
            TestGroup.COMMENTS }, executionType = ExecutionType.SANITY, description = "Verify Manager user updates comments created by admin user and status code is 200")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void managerIsAbleToUpdateHisComment() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(usersWithRoles.getOneUserWithRole(UserRole.SiteManager));
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("This is a new comment added by manager");
        restClient.withCoreAPI().usingResource(document).updateComment(commentModel, "This is the updated comment with Manager user")
                .and().field("content").is("This is the updated comment with Manager user")
                .and().field("canEdit").is(true)
                .and().field("canDelete").is(true);
        restClient.assertStatusCodeIs(HttpStatus.OK);
    }

    @TestRail(section = { TestGroup.REST_API,
            TestGroup.COMMENTS }, executionType = ExecutionType.SANITY, description = "Verify Contributor user can update his own comment and status code is 200")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void contributorIsAbleToUpdateHisComment() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(usersWithRoles.getOneUserWithRole(UserRole.SiteContributor));
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("This is a new comment added by contributor");
        String updatedContent = "This is the updated comment with Contributor user";
        restClient.withCoreAPI().usingResource(document).updateComment(commentModel, updatedContent);
        restClient.assertStatusCodeIs(HttpStatus.OK);
    }

    @TestRail(section = { TestGroup.REST_API,
            TestGroup.COMMENTS }, executionType = ExecutionType.SANITY, description = "Verify Consumer user can not update comments created by admin user and status code is 403")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void consumerIsNotAbleToUpdateComment() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUserModel);
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("This is a new comment added by admin");
        restClient.authenticateUser(usersWithRoles.getOneUserWithRole(UserRole.SiteConsumer));
        restClient.withCoreAPI().usingResource(document).updateComment(commentModel, "This is the updated comment with Consumer user");
        restClient.assertStatusCodeIs(HttpStatus.FORBIDDEN)
                                      .assertLastError().containsSummary(RestErrorModel.PERMISSION_WAS_DENIED);
    }

    @TestRail(section = { TestGroup.REST_API,
            TestGroup.COMMENTS }, executionType = ExecutionType.SANITY, description = "Verify Collaborator user can update his own comment and status code is 200")
//    @Bug(id="REPO-1011")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void collaboratorIsAbleToUpdateHisComment() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(usersWithRoles.getOneUserWithRole(UserRole.SiteCollaborator));
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("This is a new comment added by collaborator");
        restClient.withCoreAPI().usingResource(document).updateComment(commentModel, "This is the updated comment with Collaborator user");        
        restClient.assertStatusCodeIs(HttpStatus.OK);   
    }

    @TestRail(section = { TestGroup.REST_API,
            TestGroup.COMMENTS }, executionType = ExecutionType.SANITY, description = "Verify unauthenticated user gets status code 401 on update comment call")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    @Bug(id="MNT-16904")
    public void unauthenticatedUserIsNotAbleToUpdateComment() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUserModel);
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("To be updated by unauthenticated user.");
        UserModel incorrectUserModel = new UserModel("userName", "password");
        restClient.authenticateUser(incorrectUserModel)
                  .withCoreAPI().usingResource(document).updateComment(commentModel, "try to update");
        restClient.assertStatusCodeIs(HttpStatus.UNAUTHORIZED).assertLastError()
                .containsSummary(RestErrorModel.AUTHENTICATION_FAILED);
    }

    @TestRail(section = { TestGroup.REST_API, TestGroup.SANITY }, executionType = ExecutionType.SANITY, description = "Verify update comment with inexistent nodeId returns status code 404")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void canNotUpdateCommentIfNodeIdIsNotSet() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUserModel);

        FolderModel content = FolderModel.getRandomFolderModel();
        content.setNodeRef("node ref that does not exist");
        commentModel = restClient.withCoreAPI().usingResource(document).addComment("This is a new comment");
        restClient.withCoreAPI().usingResource(content).updateComment(commentModel, "This is the updated comment.");                
        restClient.assertStatusCodeIs(HttpStatus.NOT_FOUND)
                  .assertLastError().containsSummary("node ref that does not exist was not found");
    }

    @TestRail(section = { TestGroup.REST_API, TestGroup.SANITY }, executionType = ExecutionType.SANITY, description = "Verify if commentId is not set the status code is 404")
    @Test(groups = { TestGroup.REST_API, TestGroup.COMMENTS, TestGroup.SANITY })
    public void canNotUpdateCommentIfCommentIdIsNotSet() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUserModel);

        RestCommentModel comment = new RestCommentModel();
        String id = "comment id that does not exist";
        comment.setId(id);
        restClient.withCoreAPI().usingResource(document).updateComment(comment, "This is the updated comment."); 
        restClient.assertStatusCodeIs(HttpStatus.NOT_FOUND)
                  .assertLastError().containsSummary(String.format(RestErrorModel.ENTITY_NOT_FOUND, id));
    }

}
