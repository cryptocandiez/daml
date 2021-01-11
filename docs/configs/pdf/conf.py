# Copyright (c) 2021 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
# SPDX-License-Identifier: Apache-2.0

# -*- coding: utf-8 -*-
#
# Daml SDK documentation build configuration file, created by
# sphinx-quickstart on Wed Jul  5 17:39:28 2017.
#
# This file is execfile()d with the current directory set to its
# containing dir.
#
# Note that not all possible configuration values are present in this
# autogenerated file.
#
# All configuration values have a default; values that are commented out
# serve to show the default.

# If extensions (or modules to document with autodoc) are in another directory,
# add these directories to sys.path here. If the directory is relative to the
# documentation root, use os.path.abspath to make it absolute, like shown here.
#
import os
import sys
import glob
sys.path.insert(0, os.path.abspath('../static'))
sys.path.extend(map(os.path.abspath, glob.glob('packages/*')))


# -- General configuration ------------------------------------------------

# If your documentation needs a minimal Sphinx version, state it here.
#
# needs_sphinx = '1.0'

# Add any Sphinx extension module names here, as strings. They can be
# extensions coming with Sphinx (named 'sphinx.ext.*') or your custom
# ones.
extensions = [
    'sphinx.ext.autodoc',
    'sphinx.ext.extlinks'
]

# Add any paths that contain templates here, relative to this directory.
templates_path = ['_templates']

# The suffix(es) of source filenames.
# You can specify multiple suffix as a list of string:
#
# source_suffix = ['.rst', '.md']
source_suffix = '.rst'

# The master toctree document.
master_doc = 'index'

# General information about the project.
project = u'Daml SDK'
copyright = u'© Copyright 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved. Any unauthorized use, duplication or distribution is strictly prohibited.'
author = u'Digital Asset'

# The version info for the project you're documenting, acts as replacement for
# |version| and |release|, also used in various other places throughout the
# built documents.
#
# The short X.Y version.
version = u'__VERSION__'
# The full version, including alpha/beta/rc tags.
release = u'__VERSION__'

# The language for content autogenerated by Sphinx. Refer to documentation
# for a list of supported languages.
#
# This is also used if you do content translation via gettext catalogs.
# Usually you set "language" from the command line for these cases.
language = None

# List of patterns, relative to source directory, that match files and
# directories to ignore when looking for source files.
# This patterns also effect to html_static_path and html_extra_path
exclude_patterns = ['packages/daml-licenses', '**/*licenses*.rst']

# The name of the Pygments (syntax highlighting) style to use.
pygments_style = 'sphinx'

# If true, `todo` and `todoList` produce output, else they produce nothing.
todo_include_todos = False


# -- Options for HTML output ----------------------------------------------

# The theme to use for HTML and HTML Help pages.  See the documentation for
# a list of builtin themes.
html_theme = 'da_theme'
html_theme_path = ['themes']

# Theme options are theme-specific and customize the look and feel of a theme
# further.  For a list of options available for each theme, see the
# documentation.
html_theme_options = {
  'collapse_navigation': False
}

# Add any paths that contain custom static files (such as style sheets) here,
# relative to this directory. They are copied after the builtin static files,
# so a file named "default.css" will overwrite the builtin "default.css".
html_static_path = ['_static']

# Don't show "powered by sphinx"
html_show_sphinx = False

html_show_copyright = False

# Don't display the link to the sources
html_show_sourcelink = False

# -- Options for HTMLHelp output ------------------------------------------

# Output file base name for HTML help builder.
htmlhelp_basename = 'DigitalAssetSDKdoc'

# -- Options for LaTeX output ---------------------------------------------
latex_engine = 'lualatex'

latex_elements = {
    # The paper size ('letterpaper' or 'a4paper').
    #
    'papersize': 'a4paper',
    'extraclassoptions': 'openany',
    'releasename':" ",
    # Sonny, Lenny, Glenn, Conny, Rejne, Bjarne and Bjornstrup
    # 'fncychap': '\\usepackage[Lenny]{fncychap}',
    'fncychap': '\\usepackage{fncychap}',

    'figure_align':'htbp',
    # The font size ('10pt', '11pt' or '12pt').
    #
    'pointsize': '11pt',

    # Additional stuff for the LaTeX preamble.
    #
    'preamble': r'''
        %%%add number to subsubsection 2=subsection, 3=subsubsection
        %%% below subsubsection is not good idea.
        \setcounter{secnumdepth}{3}
        %
        %%%% Table of content upto 2=subsection, 3=subsubsection
        \setcounter{tocdepth}{2}

        \usepackage{graphicx}

        %%% reduce spaces for Table of contents, figures and tables
        %%% it is used "\addtocontents{toc}{\vskip -1.2cm}" etc. in the document
        \usepackage[notlot,nottoc,notlof]{}

        \usepackage{color}
        \usepackage{transparent}
        \usepackage{eso-pic}
        \usepackage{lipsum}

        \usepackage{footnotebackref} %%link at the footnote to go to the place of footnote in the text

        %% spacing between line
        \usepackage{setspace}
        %%%%\onehalfspacing
        %%%%\doublespacing
        \singlespacing

        \definecolor{headerblue}{RGB}{58, 71, 143}
        \definecolor{linkblue}{RGB}{96, 138, 216}

        %%%%%%%%%%% datetime
        \usepackage{datetime}

        \newdateformat{MonthYearFormat}{%
            \monthname[\THEMONTH], \THEYEAR}


        %% RO, LE will not work for 'oneside' layout.
        %% Change oneside to twoside in document class
        \usepackage{fancyhdr}
        \pagestyle{fancy}
        \fancyhf{}

        %%% Alternating Header for oneside
        \fancyhead[L]{\ifthenelse{\isodd{\value{page}}}{ \small \nouppercase{\leftmark} }{}}
        \fancyhead[R]{\ifthenelse{\isodd{\value{page}}}{}{ \small \nouppercase{\rightmark} }}

        %%% Alternating Header for two side
        %\fancyhead[RO]{\small \nouppercase{\rightmark}}
        %\fancyhead[LE]{\small \nouppercase{\leftmark}}

        %% for oneside: change footer at right side. If you want to use Left and right then use same as header defined above.
        \fancyfoot[R]{\ifthenelse{\isodd{\value{page}}}{{\tiny © Copyright 2020 Digital Asset (Switzerland) GmbH and/or its affiliates.} }{{\tiny © Copyright 2020 Digital Asset (Switzerland) GmbH and/or its affiliates.}}}

        %%% Alternating Footer for two side
        %\fancyfoot[RO, RE]{\scriptsize © Copyright 2020 Digital Asset (Switzerland) GmbH and/or its affiliates.}

        %%% page number
        \fancyfoot[CO, CE]{\thepage}

        \renewcommand{\headrulewidth}{0.5pt}
        \renewcommand{\footrulewidth}{0.5pt}

        \RequirePackage{tocbibind} %%% comment this to remove page number for following
        \addto\captionsenglish{\renewcommand{\contentsname}{\textcolor{headerblue}{\sffamily{\textbf{Table of contents}}}}}


        %%reduce spacing for itemize
        \usepackage{enumitem}
        \setlist{nosep}

        \usepackage{fontspec}
        \setmainfont{Karla}[
        Extension = .ttf ,
        UprightFont = *-Regular ,
        BoldFont = *-Bold ,
        ItalicFont = *-Italic ,
        BoldItalicFont = *-BoldItalic ]

        \setsansfont{Montserrat}[
        Extension = .ttf ,
        UprightFont = *-Medium ,
        BoldFont = *-Bold ,
        ItalicFont = *-MediumItalic ,
        BoldItalicFont = *-BoldItalic ]

        \setmonofont{CourierNew}[
        Extension = .ttf ,
        UprightFont = *-Regular ,
        BoldFont = *-Bold ,
        ItalicFont = *-Italic ,
        BoldItalicFont = *-BoldItalic ]

        \tymin=60pt
        \tymax=\maxdimen

        \usepackage{epstopdf}

        \epstopdfDeclareGraphicsRule{.gif}{png}{.png}{convert gif:#1[0] png:\OutputFile}
        \AppendGraphicsExtensions{.gif}
        \epstopdfDeclareGraphicsRule{.svg}{png}{.png}{convert svg:#1 png:\OutputFile}
        \AppendGraphicsExtensions{.svg}


        \usepackage{titlesec}
        \titleformat{\chapter}[display]
        {\normalfont\sffamily\huge\bfseries\color{headerblue}}
        {\chaptertitlename\ \thechapter}{20pt}{\Huge}

    ''',


    'maketitle': r'''
        \pagenumbering{Roman} %%% to avoid page 1 conflict with actual page 1

        \begin{titlepage}
            \centering

            \vspace*{40mm} %%% * is used to give space from top
            \textcolor{headerblue}{\sffamily{\textbf{\Huge {Daml SDK Documentation}}}}

            \vspace{20mm}
            \begin{figure}[!h]
                \centering
                \includegraphics[scale=0.4]{logo.png}
            \end{figure}

            \vspace{15mm}
            \Large \textcolor{headerblue}{\sffamily{\textbf{{Digital Asset}}}}

            \small Version : __VERSION__

            %% \vfill adds at the bottom
            \vfill
            \small \textit{© Copyright 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved. Any unauthorized use, duplication or distribution is strictly prohibited.}
        \end{titlepage}

        \clearpage
        \pagenumbering{roman}
        \tableofcontents
        %% \listoffigures
        %% \listoftables
        \clearpage
        \pagenumbering{arabic}
        ''',
    # Latex figure (float) alignment
    #
    # 'figure_align': 'htbp',
    'sphinxsetup': \
        'hmargin={0.7in,0.7in}, vmargin={1in,1in}, \
        verbatimwithframe=true, \
        TitleColor={RGB}{58, 71, 143}, \
        HeaderFamily=\\sffamily, \
        InnerLinkColor={RGB}{96, 138, 216}, \
        OuterLinkColor={RGB}{96, 138, 216}',

        'tableofcontents':' ',

}

latex_logo = 'logo.png'

# Grouping the document tree into LaTeX files. List of tuples
# (source start file, target name, title,
#  author, documentclass [howto, manual, or own class]).
latex_documents = [
    (master_doc, 'DigitalAssetSDK.tex', u'Daml SDK Documentation',
     u'Digital Asset', 'manual'),
]


# -- Options for manual page output ---------------------------------------

# One entry per manual page. List of tuples
# (source start file, name, description, authors, manual section).
man_pages = [
    (master_doc, 'digitalassetsdk', u'Daml SDK Documentation',
     [author], 1)
]


# -- Options for Texinfo output -------------------------------------------

# Grouping the document tree into Texinfo files. List of tuples
# (source start file, target name, title, author,
#  dir menu entry, description, category)
texinfo_documents = [
    (master_doc, 'DigitalAssetSDK', u'Daml SDK Documentation',
     author, 'DigitalAssetSDK', 'One line description of project.',
     'Miscellaneous'),
]


rst_prolog = """
.. _installer: https://github.com/digital-asset/daml/releases/download/v{release}/daml-sdk-{release}-windows.exe
.. _protobufs: https://github.com/digital-asset/daml/releases/download/v{release}/protobufs-{release}.zip
.. _api-test-tool: https://repo1.maven.org/maven2/com/daml/ledger-api-test-tool/{release}/ledger-api-test-tool-{release}.jar
""".format(release = release)

# Import the Daml lexer
def setup(sphinx):
    from pygments_daml_lexer import DAMLLexer
    sphinx.add_lexer("daml", DAMLLexer())
    from typescript import TypeScriptLexer
    sphinx.add_lexer("tsx", TypeScriptLexer())
