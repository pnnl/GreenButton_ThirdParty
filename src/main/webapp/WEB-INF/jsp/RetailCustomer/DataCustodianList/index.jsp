<%@ taglib prefix="c"       uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="security" uri="http://www.springframework.org/security/tags" %>
<!DOCTYPE html>
<html lang="en">
<head>
  <jsp:include page="../../tiles/head.jsp"/>
  <script src="<c:url value='/resources/js/jquery.min.js'/>"></script>
  <script type="text/javascript">
    $(document).ready(function(){
      // whenever one of the DC‐radios is clicked...
      $('input[name="Data_custodian"]').on('change', function(){
        // grab its URL...
        var dcUrl = $(this).attr('data-custodian-url');
        // stick it in the hidden field
        $('#dcUrl').val(dcUrl);
        // unlock the Next button
        $('#nextBtn').prop('disabled', false);
      });
    });
  </script>
</head>
<body>
  <jsp:include page="../../tiles/customer/header.jsp"/>
  <security:authentication var="principal" property="principal"/>

  <div class="container">
    <h1>Select a Data Custodian</h1>

    <form method="POST"
          action="<c:url value='/RetailCustomer/${principal.id}/ScopeSelection'/>">
      <table class="table table-striped">
        <thead>
          <tr><th>Data Custodian</th><th>Scope-URL</th></tr>
        </thead>
        <tbody>
          <c:forEach var="app" items="${applicationInformationList}">
            <tr>
              <td>
                <label>
                  <input type="radio"
                         name="Data_custodian"
                         value="${app.dataCustodianId}"
                         data‑custodian‑url="${app.dataCustodianScopeSelectionScreenURI}"/>
                  <c:out value="${app.thirdPartyApplicationName}"/>
                </label>
              </td>
              <td><c:out value="${app.dataCustodianScopeSelectionScreenURI}"/></td>
            </tr>
          </c:forEach>
        </tbody>
      </table>

      <!-- hidden field to carry DC’s authorize‑endpoint URL -->
      <input type="hidden"
             id="dcUrl"
             name="Data_custodian_URL"
             value=""/>

      <!-- disabled until a radio is checked -->
      <button type="submit"
              id="nextBtn"
              class="btn btn-primary"
              disabled>
        
      </button>
    </form>

    <hr/>
    <jsp:include page="../../tiles/footer.jsp"/>
  </div>
</body>
</html>
