package com.crowdstrike.fcscliplugin.filetypes

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

// Simple language for FCS to recognize Terraform files
class FCSTerraformLanguage private constructor() : Language("FCS_Terraform") {
    companion object {
        val INSTANCE = FCSTerraformLanguage()
    }
    
    override fun getDisplayName(): String = "Terraform"
}

// File type for Terraform files (.tf, .tfvars, .tfstate)
class FCSTerraformFileType : LanguageFileType(FCSTerraformLanguage.INSTANCE) {
    
    companion object {
        val INSTANCE = FCSTerraformFileType()
    }
    
    override fun getName(): String = "FCS_Terraform"
    
    override fun getDescription(): String = "Terraform File"
    
    override fun getDefaultExtension(): String = "tf"
    
    override fun getIcon(): Icon? = null
}
