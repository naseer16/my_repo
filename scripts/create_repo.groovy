pipeline {
    agent {
        label 'dind'    
    }
    options {
        skipDefaultCheckout()
        buildDiscarder(logRotator(artifactDaysToKeepStr: '1', artifactNumToKeepStr: '1', daysToKeepStr: '5', numToKeepStr: '10'))
    }
    stages {
      stage ('Input') {
        steps {
          script {
            def repo_name = env.REPO_NAME
            def user = env.$BUILD_USER_ID
      
            if (!repo_name?.trim() ){
              println (user)
              if (user == null ){
                error 'Invalid Request'
              }
              REPO_NAME = input(id: 'REPO_NAME', message: 'Enter Repo Name [0-9,A-Z,a-z,_,-]', parameters: [[$class: 'TextParameterDefinition', defaultValue: 'Test', description: 'Required for build', name: 'REPO_NAME']]) 
              String pattern= "^[a-zA-Z0-9_-]+\$";
              boolean isValid = REPO_NAME.matches(pattern)
              if (!isValid) {
                error 'REPO_NAME has special characters'
              }
              env.REPO_NAME = "${REPO_NAME}"
            }
            env.ORG_NAME = 'Cloud'
            env.SOURCE_REPO='Documentation'
          }
        }
      }
  
      stage ('Validate') {
        steps {
          script {
            //// Check if repo exists
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'Cloud', usernameVariable: 'git_user', passwordVariable: 'git_token']]) {  
              sh '''
              rm -rf $REPO_NAME
              git clone https://${git_user}:${git_token}@github.build.ge.com/$ORG_NAME/$REPO_Name.git 2> out.log || true
              echo "REPO_NAME="$REPO_NAME
              if [ -d $REPO_NAME ]; then
                echo "Repo with name $REPO_NAME already exists."
                exit 1
              fi
              '''
            }
          }
        }
      }
      stage ('CreateRepo') {
        steps {
          script {
            withCredentials([[$class: 'UsernamePasswordMultiBinding', credentialsId: 'Cloud', usernameVariable: 'git_user', passwordVariable: 'git_token']]) {  
                sh '''
                git config --global user.email "${git_user}@ad.ge.com"
                git config --global user.name "${git_user}"
                GIT_URL=github.build.ge.com/Cloud/$REPO_NAME.git
      
                #Create Repo
                curl -u "$git_user:$git_token" https://github.build.ge.com/api/v3/orgs/$ORG_NAME/repos -d '{"name":"'$REPO_NAME'","auto_init": true,"private": false,"has_issues": true,"has_wiki": true}'
      
                #Add Team
                #curl -k -u "$git_user:$git_token" -X PUT "https://github.build.ge.com/api/v3/teams/10023/repos/$ORG_NAME/$REPO_NAME" -d '{"permission":"push"}'
                #curl -k -u "$git_user:$git_token" -X PUT "https://github.build.ge.com/api/v3/teams/10101/repos/$ORG_NAME/$REPO_NAME" -d '{"permission":"push"}'
                #curl -k -u "$git_user:$git_token" -X PUT "https://github.build.ge.com/api/v3/teams/10044/repos/$ORG_NAME/$REPO_NAME" -d '{"permission":"push"}'
                ADMIN_TEAM_NAME=${REPO_NAME}_admin
                cmd_out="$(curl -s -X POST -u "$git_user:$git_token" -H 'Accept: application/vnd.github.loki-preview+json' --data '{"name":"'$ADMIN_TEAM_NAME'","description":"Admin team for '$REPO_NAME' repo"}' https://github.build.ge.com/api/v3/orgs/$ORG_NAME/teams)"
                #Add Team to the Repo
                TEAM_ID=$(echo $cmd_out | jq -r '.id')
                curl -k -u "$git_user:$git_token" -X PUT "https://github.build.ge.com/api/v3/teams/$TEAM_ID/repos/$ORG_NAME/$REPO_NAME" -d '{"permission":"admin"}'
      
                #Add User to the Team. If User does not exist, echo the same
                if [ "$BUILD_USER_ID" == "" ]; then
                  echo "BUILD_USER_ID not found. Setting BUILD_USER_ID=$SSO"
                  BUILD_USER_ID=$SSO
                fi
        
                cmd_out="$(curl -k -u "$git_user:$git_token" -X PUT "https://github.build.ge.com/api/v3/teams/$TEAM_ID/memberships/$BUILD_USER_ID" -d '{"role":"member"}')" 
                OUTPUT="$(echo $cmd_out | jq -r '.message')"
                if [ "$OUTPUT" == "Not Found" ]; then
                  echo "$BUILD_USER_ID not found. Login to github to be added as user."
                fi
      
                #Apply protection
                curl -s -u "$git_user:$git_token" -X PUT  -H "Accept: application/vnd.github.loki-preview+json" --data '{"required_status_checks":{"include_admins":true,"strict":false,"contexts":[]},"required_pull_request_reviews":null,"restrictions":{"users":[],"teams":[]},"enforce_admins":null}' https://github.build.ge.com/api/v3/repos/$ORG_NAME/$REPO_NAME/branches/master/protection
        
                # Add Jenkins and README.md file
                if [ -d "$SOURCE_REPO" ]; then
                  rm -rf $SOURCE_REPO
                fi
                git clone https://${git_user}:${git_token}@${GIT_URL} 
                git clone https://${git_user}:${git_token}@github.build.ge.com/$ORG_NAME/${SOURCE_REPO}.git 2> out.log || true
                if [ -f $SOURCE_REPO/common/Jenkinsfile ]; then
                  cp $SOURCE_REPO/common/Jenkinsfile $WORKSPACE/$REPO_NAME/
                fi
        
                if [ -f $SOURCE_REPO/common/README.md ]; then
                  cp $SOURCE_REPO/common/README.md $WORKSPACE/$REPO_NAME/
                else
                  echo "# $REPO_NAME" > $WORKSPACE/$REPO_NAME/README.md
                fi
        
                cd $WORKSPACE/$REPO_NAME
      
                sed -i 's/#REPO_NAME#/'"$REPO_NAME"'/g' README.md
                sed -i 's/#PROJECT_NAME#/'"$PROJECT_NAME"'/g' README.md
                sed -i 's/#PRODUCT#/'"$PRODUCT"'/g' README.md        
                sed -i 's/#DESCRIPTION#/'"$DESCRIPTION"'/g' README.md
                git add .
                git commit -am "Initial commit of the repo"
                git push origin master
                '''
            }
          }
        }
      }
    }
  }
