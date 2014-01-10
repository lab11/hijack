

PDFLATEX=TEXINPUTS=.:..: pdflatex

testprocedures.pdf: hijack.tex datasheet.cls
	$(PDFLATEX) $<
	$(PDFLATEX) $<
	$(PDFLATEX) $<

clean:
	rm -rf *.pdf *~ *.log *.dvi *.out *.aux
