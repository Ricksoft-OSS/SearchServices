package org.alfresco.rest.workflow.processes;

import org.alfresco.dataprep.CMISUtil.DocumentType;
import org.alfresco.rest.RestWorkflowTest;
import org.alfresco.rest.exception.JsonToModelConversionException;
import org.alfresco.rest.model.RestItemModel;
import org.alfresco.rest.model.RestProcessModel;
import org.alfresco.rest.requests.RestProcessesApi;
import org.alfresco.utility.data.DataUser;
import org.alfresco.utility.model.*;
import org.alfresco.utility.report.Bug;
import org.alfresco.utility.testrail.ExecutionType;
import org.alfresco.utility.testrail.annotation.TestRail;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

/**
 * @author iulia.cojocea
 */
@Test(groups = { TestGroup.REST_API, TestGroup.WORKFLOW, TestGroup.PROCESSES, TestGroup.SANITY})
public class AddProcessItemSanityTests extends RestWorkflowTest
{
    @Autowired
    private DataUser dataUser;

    @Autowired
    private RestProcessesApi processesApi;
   
    private FileModel document, document2, document3;
    private SiteModel siteModel;
    private UserModel userWhoStartsTask, assignee;
    private RestProcessModel processModel;
    private UserModel adminUser;

    private RestItemModel processItem;

    @BeforeClass(alwaysRun = true)
    public void dataPreparation() throws Exception
    {
        adminUser = dataUser.getAdminUser();
        userWhoStartsTask = dataUser.createRandomTestUser();
        assignee = dataUser.createRandomTestUser();
        siteModel = dataSite.usingUser(adminUser).createPublicRandomSite();
        document = dataContent.usingSite(siteModel).createContent(DocumentType.XML);
        dataWorkflow.usingUser(userWhoStartsTask).usingSite(siteModel).usingResource(document).createNewTaskAndAssignTo(assignee);
        processesApi.useRestClient(restClient);
    }

    @TestRail(section = {TestGroup.REST_API, TestGroup.PROCESSES }, executionType = ExecutionType.SANITY, 
            description = "Create non-existing process item")
    public void addProcessItem() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUser);
        document2 = FileModel.getRandomFileModel(FileType.TEXT_PLAIN, "file content");
        dataContent.usingSite(siteModel).createContent(document2);
        processModel = processesApi.getProcesses().getOneRandomEntry();
        processItem = processesApi.addProcessItem(processModel, document2);
        processItem.assertThat().field("createdAt").isNotEmpty()
                   .and().field("size").is(document2.getContent().length())
                   .and().field("createdBy").is(adminUser.getUsername())
                   .and().field("modifiedAt").isNotEmpty()
                   .and().field("name").is(document2.getName())
                   .and().field("modifiedBy").is(adminUser.getUsername())
                   .and().field("id").isNotEmpty()
                   .and().field("mimeType").is(document2.getFileType().mimeType);
        
        processesApi.usingRestWrapper().assertStatusCodeIs(HttpStatus.CREATED);
        processesApi.getProcessesItems(processModel).assertThat().entriesListContains("id", processItem.getId()); 
        
    }
    
    @Bug(id= "MNT-16966")
    @TestRail(section = {TestGroup.REST_API, TestGroup.PROCESSES }, executionType = ExecutionType.SANITY, 
            description = "Add process item that already exists")
    public void addProcessItemThatAlreadyExists() throws JsonToModelConversionException, Exception
    {
        restClient.authenticateUser(adminUser);
        document3 = dataContent.usingSite(siteModel).createContent(DocumentType.XML);
        processModel = processesApi.getProcesses().getOneRandomEntry();
        processItem = processesApi.addProcessItem(processModel, document3);
        processItem.assertThat().field("createdAt").isNotEmpty()
                .and().field("size").is("19")
                .and().field("createdBy").is(adminUser.getUsername())
                .and().field("modifiedAt").isNotEmpty()
                .and().field("name").is(document3.getName())
                .and().field("modifiedBy").is(adminUser.getUsername())
                .and().field("id").isNotEmpty()
                .and().field("mimeType").is(document3.getFileType().mimeType);

        processesApi.usingRestWrapper().assertStatusCodeIs(HttpStatus.CREATED);
        processesApi.getProcessesItems(processModel)
                .assertThat().entriesListContains("id", processItem.getId()).and()
                .entriesListContains("name", document3.getName());
        processItem = processesApi.addProcessItem(processModel, document3);
        processesApi.usingRestWrapper().assertStatusCodeIs(HttpStatus.BAD_REQUEST);
    }
}
